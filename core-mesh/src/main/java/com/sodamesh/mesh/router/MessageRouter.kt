package com.sodamesh.mesh.router

import com.sodamesh.mesh.model.MeshPacket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Bitchat-method mesh router: store-and-forward over [MeshSender] with
 * courier fallback into [MeshOutbox].
 *
 * - [route] (egress): prefers a live link to the destination; when nobody
 *   suitable is connected, directed packets are deposited for courier
 *   delivery once the peer is seen again.
 * - [onReceive] (ingress): dedup via [DedupCache], emit to [inbound] when the
 *   packet is for this node, otherwise schedule a jittered relay per
 *   [RelayController] (TTL-1 copy, ingress excluded).
 *
 * Wiring (owned by sibling agents, defaults are no-ops so this class works
 * unconfigured in unit tests):
 * - Transport (Agent 4, GATT): set [sender] (+ [links] for live-peer lookup).
 * - Outbox/courier (Agent 6, `com.sodamesh.mesh.store.Outbox`): set [outbox],
 *   e.g. `{ peerId, payload -> outbox.enqueue(peerId, payload) is OutboxNote.Enqueued }`.
 * - Identity: call [setLocalId] with this node's 8-byte id once known.
 *
 * Pure JVM + coroutines (no Android deps). Hilt: [DedupCache] and
 * [RelayController] are `@Singleton`-injectable, so this constructor is
 * fully satisfiable; the [sender]/[outbox]/[links] ports are plain vars.
 */
@Singleton
class MessageRouter @Inject constructor(
    private val dedup: DedupCache,
    private val relay: RelayController,
) {

    /** Transport port (GATT). Empty [targets] = flood all connected peers. */
    fun interface MeshSender {
        /** Transmits [packet] to [targets] (empty = flood); returns peers reached. */
        suspend fun broadcast(packet: MeshPacket, targets: List<String>): Int
    }

    /** Courier port (persistent outbox). Return true when the payload was queued. */
    fun interface MeshOutbox {
        suspend fun depositForPeer(peerId: String, payload: ByteArray): Boolean
    }

    /** Live-link directory. */
    interface LinkView {
        fun livePeers(): Set<String>
        fun isLive(peerId: String): Boolean
    }

    /** Egress outcome. */
    sealed interface RouteResult {
        /** Sent over a live link. */
        data object SentLive : RouteResult

        /** No live link; queued for courier delivery. */
        data object Queued : RouteResult

        /** No live link and nothing queueable (e.g. broadcast with no peers). */
        data object Dropped : RouteResult
    }

    /** No-op transport (unit tests / pre-wiring). */
    val noOpSender: MeshSender = MeshSender { _, _ -> 0 }

    /** No-op outbox (unit tests / pre-wiring). */
    val noOpOutbox: MeshOutbox = MeshOutbox { _, _ -> false }

    /** Empty link directory (unit tests / pre-wiring). */
    val emptyLinks: LinkView = object : LinkView {
        override fun livePeers(): Set<String> = emptySet()
        override fun isLive(peerId: String): Boolean = false
    }

    /** Transport used by [route] and scheduled relays. Replace when GATT lands. */
    @Volatile
    var sender: MeshSender = noOpSender

    /** Courier outbox used by [route] fallback. Replace with the store Outbox. */
    @Volatile
    var outbox: MeshOutbox = noOpOutbox

    /** Live-link directory used by [route]. Replace with the connection manager. */
    @Volatile
    var links: LinkView = emptyLinks

    /** Scope for jittered relay launches (replaceable in tests via `TestScope`). */
    @Volatile
    var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** This node's 8-byte id, or null until [setLocalId]. Guarded by volatility. */
    @Volatile
    private var localId: ByteArray? = null

    private val _inbound = Channel<MeshPacket>(Channel.UNLIMITED)

    /**
     * Packets addressed to this node (broadcasts + directed-to-me), in arrival order.
     *
     * Channel-backed (unlimited buffer): values queue even with no collector yet,
     * so bursts never suspend or drop on the ingress path. Single-consumer
     * fan-out — the app/GATT layer owns the one collector.
     */
    val inbound: Flow<MeshPacket> = _inbound.receiveAsFlow()

    /** Sets this node's 8-byte identity used by [isMine]. */
    fun setLocalId(id: ByteArray) {
        require(id.size == MeshPacket.ID_LEN) { "localId must be ${MeshPacket.ID_LEN} bytes" }
        localId = id.copyOf()
    }

    /** True when [packet] should be delivered locally: broadcasts always, directed iff recipient == me. */
    fun isMine(packet: MeshPacket): Boolean {
        val recipient = packet.recipientId ?: return true
        val me = localId ?: return false
        return recipient.contentEquals(me)
    }

    /**
     * Egress: sends [packet] over a live link when possible, else deposits it
     * for courier delivery.
     *
     * - Directed ([targetId] or `packet.recipientId`): live peer -> [SentLive];
     *   otherwise `outbox.depositForPeer(peerHex, packet.toBytes())` -> [Queued]/[Dropped].
     * - Broadcast with no destination: live peers -> [SentLive]; nobody live ->
     *   [Dropped] (courier needs a destination peer).
     */
    suspend fun route(packet: MeshPacket, targetId: String? = null): RouteResult {
        val peer = targetId ?: packet.recipientId?.let(DedupCache::toHex)
        if (peer != null) {
            if (links.isLive(peer)) {
                val sent = runCatching { sender.broadcast(packet, listOf(peer)) }.getOrDefault(0)
                if (sent > 0) return RouteResult.SentLive
            }
            val queued = runCatching { outbox.depositForPeer(peer, packet.toBytes()) }.getOrDefault(false)
            return if (queued) RouteResult.Queued else RouteResult.Dropped
        }
        val live = links.livePeers()
        if (live.isNotEmpty()) {
            val sent = runCatching { sender.broadcast(packet, live.toList()) }.getOrDefault(0)
            if (sent > 0) return RouteResult.SentLive
        }
        return RouteResult.Dropped
    }

    /**
     * Ingress: dedup, deliver-if-mine, else schedule a jittered relay.
     *
     * @param ingressId peer the packet arrived from (always excluded from relay targets).
     * @param neighbors explicit neighbour ids for fanout selection; when empty,
     *   [links] live peers (minus ingress) are used as flood targets.
     * @param degree neighbour count used for TTL-clamp/fanout when [neighbors] is empty.
     * @return false for duplicates (dropped); true when accepted (delivered and/or relay scheduled).
     */
    fun onReceive(
        packet: MeshPacket,
        ingressId: String? = null,
        neighbors: List<String> = emptyList(),
        degree: Int = neighbors.size,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!dedup.shouldRelay(packet, nowMs)) return false
        if (isMine(packet)) _inbound.trySend(packet)

        val decision = relay.decide(packet, degree, ingressId, neighbors)
        if (!decision.shouldRelay) return true
        val targets = when {
            neighbors.isNotEmpty() -> decision.targets
            else -> links.livePeers().filter { it != ingressId }
        }
        // Known neighbourhood with nobody left to send to: nothing to do.
        if (neighbors.isNotEmpty() && targets.isEmpty()) return true
        // Count-only mode with no live peers either: nothing to do.
        if (neighbors.isEmpty() && targets.isEmpty()) return true

        val forwarded = packet.copy(ttl = decision.newTtl)
        val delayMs = decision.delayMs
        scope.launch {
            delay(delayMs)
            runCatching { sender.broadcast(forwarded, targets) }
        }
        return true
    }

    /** Cancels pending relay launches and closes [inbound] (tests / shutdown). */
    fun close() {
        scope.cancel()
        _inbound.close()
    }

    /**
     * Ingress decap helper for the vendor path (wired by the coordinator):
     * strips the [MeshPacket] envelope off reassembled GATT bytes.
     *
     * - Valid envelope: runs [onReceive] (dedup + deliver-if-mine + jittered
     *   relay) and returns the order payload iff the packet is for this node
     *   ([isMine]: broadcasts always, directed iff recipient == me) AND
     *   carries `TYPE_ORDER`. Valid non-order packets (ANNOUNCE/ACK) and
     *   valid order packets for other nodes return null (no local delivery;
     *   relay was still scheduled).
     * - Legacy raw payload (pre-envelope bytes, [MeshPacket.fromBytesOrNull]
     *   fails): returned as-is (passthrough) so old senders keep working. No
     *   dedup/relay bookkeeping is applied to passthrough bytes.
     *
     * @param bytes reassembled GATT payload (enveloped or legacy raw).
     * @param ingressId peer the bytes arrived from (excluded from relay targets).
     */
    fun ingressPayload(
        bytes: ByteArray,
        ingressId: String? = null,
        neighbors: List<String> = emptyList(),
        degree: Int = neighbors.size,
        nowMs: Long = System.currentTimeMillis(),
    ): ByteArray? {
        val packet = MeshPacket.fromBytesOrNull(bytes) ?: return bytes.copyOf()
        onReceive(packet, ingressId, neighbors, degree, nowMs)
        if (!isMine(packet)) return null
        return MeshPacketFactory.extractOrderPayload(packet)
    }

    companion object {
        /** Initial [inbound] backlog hint. The channel itself is unlimited; this is documentation only. */
        const val INBOUND_BUFFER = 64
    }
}
