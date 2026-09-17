package com.sodamesh.mesh.router

import com.sodamesh.mesh.model.MeshPacket
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.random.Random

/**
 * Forwarding decision for one inbound packet.
 *
 * @property shouldRelay false when TTL is exhausted ([MeshPacket.ttl] <= 0).
 * @property newTtl TTL to stamp on the forwarded copy ([MeshPacket.copy]).
 * @property delayMs random jitter to wait before transmitting (storm spreading).
 * @property fanoutCount how many peers to forward to (subset in dense meshes).
 * @property targets explicit peer ids to forward to, ingress already excluded;
 *   empty when the caller only passed a peer count (flood via transport).
 */
data class RelayDecision(
    val shouldRelay: Boolean,
    val newTtl: Int,
    val delayMs: Long,
    val fanoutCount: Int,
    val targets: List<String>,
)

/**
 * Bitchat-method relay policy (TTL 7 mesh, cf. `MeshConfig.TTL_DEFAULT`).
 *
 * - Broadcast (`recipientId == null`): dense meshes (`degree >= 6`) clamp the
 *   forwarded TTL to [DENSE_TTL_CAP] (5) and forward to a `ceil(log2(degree))`
 *   subset; thin meshes (`degree <= 2`) keep full TTL depth and fan out to
 *   everyone. TTL is decremented per hop; `ttl <= 0` never relays.
 * - Directed (`recipientId != null`, e.g. ORDER/ACK): always relay with
 *   `TTL - 1` to every eligible peer (no clamp, no subsetting).
 * - Ingress peer ([ingressId]) is always excluded from [RelayDecision.targets].
 * - Every decision carries [JITTER_MIN_MS]..[JITTER_MAX_MS] ms of random delay.
 *
 * Pure JVM (no Android deps). Directed-ness is derived from
 * `recipientId != null` (the wire `HAS_RECIPIENT` flag), not from [MeshPacket.type],
 * so ANNOUNCE/ORDER/ACK all behave correctly even if new types are added.
 */
@Singleton
class RelayController @Inject constructor() {

    /**
     * Relay decision for [packet] seen from [ingressId] with [degree] live neighbours.
     *
     * @param neighbors explicit neighbour ids for fanout selection; when empty,
     *   only [degree] is used and [RelayDecision.targets] is empty (caller floods).
     * @param random jitter/subset source (inject a seeded instance in tests).
     */
    fun decide(
        packet: MeshPacket,
        degree: Int,
        ingressId: String? = null,
        neighbors: List<String> = emptyList(),
        random: Random = Random.Default,
    ): RelayDecision {
        if (packet.ttl <= 0) {
            return RelayDecision(
                shouldRelay = false,
                newTtl = 0,
                delayMs = 0,
                fanoutCount = 0,
                targets = emptyList(),
            )
        }
        val delayMs = random.nextLong(JITTER_MIN_MS, JITTER_MAX_MS + 1)
        val eligible = if (ingressId == null) neighbors else neighbors.filter { it != ingressId }
        // Peers we could send to when only a count is known (ingress occupies one slot).
        val poolSize = if (neighbors.isNotEmpty()) {
            eligible.size
        } else {
            (degree - if (ingressId != null && degree > 0) 1 else 0).coerceAtLeast(0)
        }

        if (packet.recipientId != null) {
            // Directed: always relay with TTL-1, full fanout, ingress excluded.
            return RelayDecision(
                shouldRelay = true,
                newTtl = decremented(packet.ttl),
                delayMs = delayMs,
                fanoutCount = poolSize,
                targets = eligible,
            )
        }

        // Broadcast: dense clamp + subset fanout, thin keeps full depth.
        val decremented = decremented(packet.ttl)
        val newTtl = if (degree >= DENSE_DEGREE) minOf(decremented, DENSE_TTL_CAP) else decremented
        val fanout = when {
            poolSize <= 0 -> 0
            degree <= THIN_DEGREE -> poolSize
            else -> minOf(poolSize, ceil(log2(degree.toDouble())).toInt().coerceAtLeast(1))
        }
        return RelayDecision(
            shouldRelay = true,
            newTtl = newTtl,
            delayMs = delayMs,
            fanoutCount = fanout,
            targets = if (neighbors.isEmpty()) emptyList() else eligible.shuffled(random).take(fanout),
        )
    }

    /** True for directed packets (ORDER/ACK carry a recipient). */
    fun isDirected(packet: MeshPacket): Boolean = packet.recipientId != null

    companion object {
        /** Legal TTL ceiling; mirrors `MeshConfig.TTL_DEFAULT`. */
        const val TTL_MAX = 7

        /** Neighbour count at/above which the mesh counts as dense. */
        const val DENSE_DEGREE = 6

        /** Forwarded-TTL cap applied in dense meshes. */
        const val DENSE_TTL_CAP = 5

        /** Neighbour count at/below which the mesh counts as thin (full depth + full fanout). */
        const val THIN_DEGREE = 2

        /** Jitter window bounds (ms, inclusive). */
        const val JITTER_MIN_MS = 10L
        const val JITTER_MAX_MS = 220L

        /** Clamp an incoming TTL into `[0, max]`. */
        fun clampTtl(ttl: Int, max: Int = TTL_MAX): Int = ttl.coerceIn(0, max)

        /** Relay iff the packet still has hops left and is unseen. */
        fun shouldRelay(ttl: Int, alreadySeen: Boolean): Boolean = !alreadySeen && ttl > 0

        /** TTL to stamp on the forwarded copy (never below zero). */
        fun decremented(ttl: Int): Int = (ttl - 1).coerceAtLeast(0)
    }
}
