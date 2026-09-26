package com.sodamesh.mesh

import android.util.Log
import com.google.gson.Gson
import com.sodamesh.data.OrderEntity
import com.sodamesh.data.OrderRepository
import com.sodamesh.data.OrderStatus
import com.sodamesh.data.PrefsStore
import com.sodamesh.mesh.model.Drink
import com.sodamesh.mesh.model.OrderCodec
import com.sodamesh.mesh.transport.GattServerManager
import com.sodamesh.notify.OrderNotifier
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Vendor-side inbound order pipeline.
 *
 * Consumes reassembled mesh payloads, validates them via [OrderCodec.decode],
 * persists them as [OrderEntity] with [OrderStatus.NEW] through
 * [OrderRepository.incoming], re-maps them to UI-ready [VendorInboundOrder]s
 * (display names resolved via [Drink.byId]), and fires a loud heads-up via
 * [OrderNotifier.notify].
 *
 * Wiring adaptation (read before touching DI):
 * - [GattServerManager] exposes inbound payloads ONLY as the `onPacket`
 *   constructor lambda and has NO Hilt binding; it is constructed manually by
 *   `MeshRoleImpl.startAdvertising()`. Injecting it here would break the Hilt
 *   graph (missing binding) and create a provision cycle
 *   (server needs `pipeline::handlePacket` as its `onPacket`). So this class
 *   does NOT take it in the constructor. Instead the BLE owner forwards
 *   packets via [handlePacket] (from the `onPacket` lambda) or
 *   [attachPacketFlow] (tests / alternate sources), and hands the server back
 *   for ACKs via [bindGatt]. See the wiring note returned with this file.
 * - This file lives in `main` so it must NOT reference
 *   `com.sodamesh.vendor.VendorOrder` (vendor flavor source set; invisible to
 *   the customer flavor build). It exposes [incomingOrders] of
 *   [VendorInboundOrder]; the vendor flavor maps those to `VendorOrder` and
 *   calls `VendorViewModel.onOrderReceived()`. No dependency on the VM here,
 *   so no cycle.
 * - Dedup is by `orderId` in a local LRU+expiry map that mirrors
 *   `DedupCache` semantics (`sender+ts+type+digest` key, 1000 entries, 5 min
 *   window). Drop-in swap: `dedupCache.shouldRelay("order:${order.orderId}")`.
 */
@Singleton
class VendorPipeline @Inject constructor(
    private val repository: OrderRepository,
    private val notifier: OrderNotifier,
    private val prefsStore: PrefsStore?,
    private val gson: Gson?,
) {

    companion object {
        private const val TAG = "VendorPipeline"
        private const val PACKET_BUFFER = 64
        private const val ACK_BUFFER = 16

        /** Token placed after `orderId|` in [buildAck] for an acceptance. */
        const val ACK_ACCEPTED = "ACCEPTED"

        /** Token placed after `orderId|` in [buildAck] for a rejection. */
        const val ACK_REJECTED = "REJECTED"

        /** Dedup window per orderId; mirrors `DedupCache.EXPIRY_MS_DEFAULT`. */
        const val DEDUP_WINDOW_MS = 5L * 60 * 1000

        /** Max tracked orderIds; mirrors `DedupCache.MAX_ENTRIES_DEFAULT`. */
        const val DEDUP_MAX_ENTRIES = 1000
    }

    // Buffer of raw reassembled payloads from handlePacket()/attachPacketFlow().
    private val packets = MutableSharedFlow<ByteArray>(extraBufferCapacity = PACKET_BUFFER)

    // Optional Flow source installed via attachPacketFlow() before start().
    @Volatile
    private var attachedFlow: Flow<ByteArray>? = null

    private val _incomingOrders = MutableSharedFlow<VendorInboundOrder>(extraBufferCapacity = PACKET_BUFFER)

    /**
     * UI hook for vendor routes: collect this and map each element to
     * `VendorOrder`, then call `VendorViewModel.onOrderReceived()`.
     * Never completes; replays nothing (new subscribers see new orders only).
     */
    val incomingOrders: SharedFlow<VendorInboundOrder> = _incomingOrders.asSharedFlow()

    private val _acks = MutableSharedFlow<VendorAck>(extraBufferCapacity = ACK_BUFFER)

    /** Outcomes of [sendAck]/[acceptOrder]/[rejectOrder] for the UI to observe. */
    val acks: SharedFlow<VendorAck> = _acks.asSharedFlow()

    /** Alias kept for the requested `observeAcks` API name. */
    fun observeAcks(): Flow<VendorAck> = acks

    /**
     * GATT server bound via [bindGatt]. ACKs go out through
     * `GattServerManager.sendAck(payload)` (suspend, fragments + notifies
     * every subscribed device). Null until bound -> [sendAck] returns false.
     */
    @Volatile
    var gattServerManager: GattServerManager? = null
        private set

    /** Called by the BLE owner (e.g. `MeshRoleImpl`) with its live server. */
    fun bindGatt(manager: GattServerManager?) {
        gattServerManager = manager
    }

    // orderId -> first-seen epoch millis. Guarded by [dedupLock].
    private val dedupLock = Any()
    private val seenOrderIds = object : LinkedHashMap<String, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean =
            size > DEDUP_MAX_ENTRIES
    }

    @Volatile
    private var collectJob: Job? = null

    /**
     * Direct callback entry point. Call this from the `GattServerManager`
     * `onPacket` lambda: `GattServerManager(ctx, onPacket = pipeline::handlePacket)`.
     * Never throws; returns false when the buffer is full and the packet was dropped.
     */
    fun handlePacket(bytes: ByteArray): Boolean =
        packets.tryEmit(bytes.copyOf())

    /**
     * Alternate/test entry point: installs a [Flow] of reassembled payloads
     * to be forwarded into the pipeline once [start] runs. Call before
     * [start]; replaces any previously attached flow.
     */
    fun attachPacketFlow(flow: Flow<ByteArray>) {
        attachedFlow = flow
    }

    /**
     * Starts collecting inbound packets on [scope]. Idempotent: returns the
     * active job when already started. Cancel the returned job (or call
     * [stop]) to halt.
     */
    fun start(scope: CoroutineScope): Job {
        collectJob?.takeIf { it.isActive }?.let { return it }
        val job = scope.launch {
            // Forward the attached Flow source, if any, into the shared buffer.
            attachedFlow?.let { flow ->
                launch { flow.collect { packets.emit(it) } }
            }
            packets.collect { processPacket(it) }
        }
        collectJob = job
        return job
    }

    /** Halts collection started by [start]. */
    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }

    /**
     * Builds the vendor ACK payload: `"orderId|ACCEPTED"` (or `"|REJECTED"`),
     * UTF-8. The customer side parses this contract (orderId = text before
     * the last `|`).
     */
    fun buildAck(orderId: String, accepted: Boolean): ByteArray {
        require(orderId.isNotBlank()) { "orderId must not be blank" }
        val token = if (accepted) ACK_ACCEPTED else ACK_REJECTED
        return "$orderId|$token".toByteArray(Charsets.UTF_8)
    }

    /**
     * Sends a vendor decision over the mesh via
     * `GattServerManager.sendAck(payload)` (suspend; fragments + notifies all
     * subscribed devices). Emits a [VendorAck] on [acks].
     *
     * @return true when at least one subscriber was notified; false when no
     *   server is bound ([bindGatt]), the server is down, or nobody is
     *   subscribed.
     */
    suspend fun sendAck(orderId: String, accepted: Boolean): Boolean {
        val server = gattServerManager
        val delivered = if (server == null) {
            Log.w(TAG, "sendAck: no GATT server bound, dropping ACK for $orderId")
            false
        } else {
            runCatching { server.sendAck(buildAck(orderId, accepted)) }
                .onFailure { Log.w(TAG, "sendAck failed for $orderId", it) }
                .getOrDefault(false)
        }
        _acks.emit(VendorAck(orderId = orderId, accepted = accepted, delivered = delivered))
        return delivered
    }

    /**
     * Vendor accepts [orderId]: persists [OrderStatus.ACCEPTED], sends the ACK
     * over the mesh, and withdraws the heads-up notification.
     *
     * @return true when the ACK was delivered to >= 1 subscriber.
     */
    suspend fun acceptOrder(orderId: String): Boolean {
        runCatching { repository.setStatus(orderId, OrderStatus.ACCEPTED) }
            .onFailure { Log.w(TAG, "acceptOrder: setStatus failed for $orderId", it) }
        val delivered = sendAck(orderId, accepted = true)
        runCatching { notifier.cancelOnAccept(orderId) }
        return delivered
    }

    /**
     * Vendor rejects [orderId]: persists [OrderStatus.REJECTED] and sends the
     * ACK over the mesh.
     *
     * @return true when the ACK was delivered to >= 1 subscriber.
     */
    suspend fun rejectOrder(orderId: String): Boolean {
        runCatching { repository.setStatus(orderId, OrderStatus.REJECTED) }
            .onFailure { Log.w(TAG, "rejectOrder: setStatus failed for $orderId", it) }
        return sendAck(orderId, accepted = false)
    }

    private suspend fun processPacket(bytes: ByteArray) {
        val order = try {
            OrderCodec.decode(bytes)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Dropping malformed order payload: ${e.message}")
            return
        }

        // Dedup retransmits by orderId (mesh TTL re-delivery).
        if (!markSeenOrFresh(order.orderId)) {
            Log.i(TAG, "Dropping duplicate order ${order.orderId}")
            return
        }

        // Vendor only handles orders for its own shop; blank stored shopId = accept all.
        val ownShopId = runCatching { prefsStore?.shopId?.first().orEmpty() }.getOrDefault("")
        if (!ownShopId.isNullOrBlank() && order.shopId != ownShopId) {
            Log.i(TAG, "Ignoring order ${order.orderId} for shop ${order.shopId}")
            return
        }

        val gson = gson ?: Gson()
        val entity = OrderEntity(
            orderId = order.orderId,
            shopId = order.shopId,
            customerName = order.customerName,
            itemsJson = gson.toJson(order.items),
            totalCents = order.totalCents.toLong(),
            status = OrderStatus.NEW,
            ts = order.ts,
        )
        runCatching { repository.incoming(entity) }
            .onFailure {
                Log.w(TAG, "incoming() failed for ${order.orderId}", it)
                return
            }

        _incomingOrders.emit(order.toInbound())

        runCatching {
            notifier.notify(
                orderId = order.orderId,
                title = "New order \u2014 ${order.customerName}",
                body = "${order.items.size} items \u2022 ${formatCents(order.totalCents.toLong())}",
            )
        }.onFailure { Log.w(TAG, "notify failed for ${order.orderId}", it) }
    }

    /** Dedup check-and-record; true when [orderId] is unseen (caller delivers). */
    private fun markSeenOrFresh(orderId: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        synchronized(dedupLock) {
            val last = seenOrderIds[orderId]
            // nowMs < last (clock moved back) counts as seen to avoid storms.
            if (last != null && (nowMs < last || nowMs - last < DEDUP_WINDOW_MS)) return false
            seenOrderIds[orderId] = nowMs
            while (seenOrderIds.size > DEDUP_MAX_ENTRIES) {
                seenOrderIds.keys.firstOrNull()?.let(seenOrderIds::remove) ?: break
            }
            true
        }

    private fun formatCents(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val abs = kotlin.math.abs(cents)
        return "$sign$${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }
}

/** UI-ready vendor line item: display name resolved via [Drink.byId]. */
data class VendorInboundItem(
    val name: String,
    val qty: Int,
    val unitPriceCents: Long,
) {
    val lineTotalCents: Long get() = qty * unitPriceCents
}

/**
 * UI-ready inbound order emitted on [VendorPipeline.incomingOrders].
 *
 * Main-source-set equivalent of the vendor flavor's `VendorOrder` (which this
 * module cannot reference without breaking the customer build). The vendor
 * flavor maps this 1:1 and forwards it to `VendorViewModel.onOrderReceived()`.
 */
data class VendorInboundOrder(
    val orderId: String,
    val customerName: String,
    val items: List<VendorInboundItem>,
    val totalCents: Long,
    val receivedAtMillis: Long = System.currentTimeMillis(),
)

/** Outcome of one [VendorPipeline.sendAck] call. */
data class VendorAck(
    val orderId: String,
    val accepted: Boolean,
    val delivered: Boolean,
)

private fun com.sodamesh.mesh.model.SodaOrder.toInbound(): VendorInboundOrder =
    VendorInboundOrder(
        orderId = orderId,
        customerName = customerName,
        items = items.map { item ->
            val drink = Drink.byId(item.drinkId)
            VendorInboundItem(
                name = drink?.name ?: item.drinkId,
                qty = item.qty,
                unitPriceCents = drink?.priceCents?.toLong() ?: 0L,
            )
        },
        totalCents = totalCents.toLong(),
    )
