package com.sodamesh.vendor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Local order status. ACCEPTED / REJECTED represent the ACK sent back over the mesh. */
enum class VendorOrderStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
}

data class VendorOrderItem(
    val name: String,
    val qty: Int,
    val unitPriceCents: Long,
) {
    val lineTotalCents: Long get() = qty * unitPriceCents
}

/**
 * Local vendor-side order model. Deliberately decoupled from any core-mesh
 * transport type (e.g. SodaOrder) so the mesh agent can map inbound payloads
 * via [onOrderReceived] without creating a module coupling.
 */
data class VendorOrder(
    val orderId: String,
    val customerName: String,
    val items: List<VendorOrderItem>,
    val totalCents: Long,
    val status: VendorOrderStatus = VendorOrderStatus.PENDING,
    val receivedAtMillis: Long = System.currentTimeMillis(),
) {
    val totalFormatted: String get() = formatCents(totalCents)
}

fun formatCents(cents: Long): String {
    val sign = if (cents < 0) "-" else ""
    val abs = kotlin.math.abs(cents)
    return "$sign$${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}

@HiltViewModel
class VendorViewModel @Inject constructor() : ViewModel() {

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    // Inbound orders. Mesh layer posts decoded orders via onOrderReceived().
    private val _orders = MutableStateFlow<List<VendorOrder>>(emptyList())
    val orders: StateFlow<List<VendorOrder>> = _orders.asStateFlow()

    val pendingCount: StateFlow<Int> =
        orders
            .map { list -> list.count { it.status == VendorOrderStatus.PENDING } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Last accept/reject ACK (orderId -> decision). Cleared via [clearAck]. */
    private val _lastAck = MutableStateFlow<Pair<String, VendorOrderStatus>?>(null)
    val lastAck: StateFlow<Pair<String, VendorOrderStatus>?> = _lastAck.asStateFlow()

    fun startAdvertising() {
        _isAdvertising.value = true
    }

    fun stopAdvertising() {
        _isAdvertising.value = false
    }

    fun toggleAdvertising() {
        _isAdvertising.value = !_isAdvertising.value
    }

    /** Entry point for the mesh/transport owner to publish an inbound order. */
    fun onOrderReceived(order: VendorOrder) {
        _orders.update { current ->
            // De-dupe re-deliveries of the same order id (mesh TTL retransmits).
            if (current.any { it.orderId == order.orderId }) current else current + order
        }
    }

    fun acceptOrder(orderId: String) {
        applyDecision(orderId, VendorOrderStatus.ACCEPTED)
    }

    fun rejectOrder(orderId: String) {
        applyDecision(orderId, VendorOrderStatus.REJECTED)
    }

    fun clearAck() {
        _lastAck.value = null
    }

    fun dismissOrder(orderId: String) {
        _orders.update { current -> current.filterNot { it.orderId == orderId } }
    }

    private fun applyDecision(orderId: String, decision: VendorOrderStatus) {
        require(decision != VendorOrderStatus.PENDING) { "decision must be an ACK state" }
        var applied = false
        _orders.update { current ->
            current.map { order ->
                if (order.orderId == orderId && order.status == VendorOrderStatus.PENDING) {
                    applied = true
                    order.copy(status = decision)
                } else {
                    order
                }
            }
        }
        if (applied) _lastAck.value = orderId to decision
    }
}
