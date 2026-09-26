package com.sodamesh.customer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sodamesh.common.Cart
import com.sodamesh.data.MeshSender
import com.sodamesh.mesh.MeshConfig
import com.sodamesh.mesh.model.Drink
import com.sodamesh.mesh.model.OrderCodec
import com.sodamesh.mesh.model.SodaOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Lifecycle of an outbound customer order over the mesh. */
enum class SendState {
    /** Nothing sent yet (or reset after a completed attempt). */
    Idle,

    /** Simulating/performing BLE peer discovery before the first hop. */
    Scanning,

    /** Payload handed to [MeshSender]; awaiting outbox acceptance. */
    Sending,

    /** Accepted into the mesh outbox; waiting for the vendor ACK. */
    Relayed,

    /** Vendor ACK observed for [CustomerViewModel.lastOrder]. */
    Delivered,

    /** Terminal failure — see [CustomerViewModel.errorMessage]. */
    Failed,
}

/**
 * Customer ordering ViewModel (customer flavor).
 *
 * State:
 * - [menu] is the canonical [Drink.MENU] from core-mesh.
 * - [cart] maps `drinkId -> qty`; [totalCents] / [cartCount] are derived via [Cart].
 * - [sendState] tracks the outbound lifecycle Idle/Scanning/Sending/Relayed/Delivered/Failed.
 *
 * Transport decoupling: this VM depends only on [MeshSender]
 * (`com.sodamesh.data` — the transport seam). It never references `MessageRouter`
 * or any BLE type. The real BLE implementation is attached via [attachSender]
 * by whoever owns the mesh stack (or a Hilt binding added later); tests and
 * previews attach a fake. A null sender fails the send with a clear message
 * instead of crashing.
 *
 * ACK mapping: `MeshSender.sendOrder` success means "accepted into the outbox",
 * so the VM moves to [SendState.Relayed] and then awaits the matching orderId on
 * [MeshSender.observeAck] (bounded by [ACK_WAIT_MS]) before [SendState.Delivered].
 * A timeout leaves the state at [SendState.Relayed] — the order is in the mesh,
 * the vendor confirmation just hasn't been observed yet.
 */
@HiltViewModel
class CustomerViewModel @Inject constructor() : ViewModel() {

    /** Transport seam. Null until [attachSender] is called. */
    var meshSender: MeshSender? = null
        private set

    fun attachSender(sender: MeshSender) {
        meshSender = sender
    }

    fun detachSender() {
        meshSender = null
    }

    val menu: List<Drink> = Drink.MENU

    private val _cart = MutableStateFlow<Map<String, Int>>(emptyMap())
    val cart: StateFlow<Map<String, Int>> = _cart.asStateFlow()

    val cartCount: StateFlow<Int> =
        cart.map { Cart.count(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val totalCents: StateFlow<Int> =
        cart.map { Cart.totalCents(it, menu) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _customerName = MutableStateFlow("")
    val customerName: StateFlow<String> = _customerName.asStateFlow()

    private val _sendState = MutableStateFlow(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    private val _lastOrder = MutableStateFlow<SodaOrder?>(null)
    val lastOrder: StateFlow<SodaOrder?> = _lastOrder.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var sendJob: Job? = null

    // ---- Cart mutations ----------------------------------------------------

    fun add(drinkId: String) {
        _cart.update { Cart.add(it, drinkId) }
    }

    fun removeOne(drinkId: String) {
        _cart.update { Cart.removeOne(it, drinkId) }
    }

    fun setQty(drinkId: String, qty: Int) {
        _cart.update { Cart.setQty(it, drinkId, qty) }
    }

    fun clearCart() {
        _cart.value = Cart.clear()
    }

    fun setCustomerName(name: String) {
        _customerName.value = name
    }

    // ---- Send lifecycle ----------------------------------------------------

    /** Resets the send pipeline to [SendState.Idle]; cancels any in-flight attempt. */
    fun resetSendState() {
        sendJob?.cancel()
        sendJob = null
        _sendState.value = SendState.Idle
        _errorMessage.value = null
    }

    /** Clears cart + order + send state for a fresh order. */
    fun startNewOrder() {
        resetSendState()
        _lastOrder.value = null
        _cart.value = Cart.clear()
    }

    /** Retry is a fresh attempt through the same pipeline. */
    fun retrySend() = sendOrder()

    fun sendOrder() {
        val current = _sendState.value
        if (current == SendState.Scanning || current == SendState.Sending) return
        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            val sender = meshSender
            val name = _customerName.value.trim()
            val items = Cart.toOrderItems(_cart.value)

            if (items.isEmpty()) {
                fail("Your cart is empty — add a drink first.")
                return@launch
            }
            if (name.isBlank()) {
                fail("Enter your name so the vendor knows whose order this is.")
                return@launch
            }
            if (sender == null) {
                fail("Mesh sender not attached — cannot reach the vendor yet.")
                return@launch
            }

            val order = try {
                SodaOrder.create(
                    shopId = MeshConfig.SHOP_ID,
                    customerName = name,
                    items = items,
                    menu = menu,
                )
            } catch (e: IllegalArgumentException) {
                fail(e.message ?: "Invalid order.")
                return@launch
            }

            val bytes = try {
                OrderCodec.encode(order)
            } catch (e: IllegalArgumentException) {
                fail(e.message ?: "Could not encode order.")
                return@launch
            }

            _lastOrder.value = order
            _errorMessage.value = null

            _sendState.value = SendState.Scanning
            delay(SCAN_DELAY_MS)

            _sendState.value = SendState.Sending
            val accepted = try {
                sender.sendOrder(bytes)
            } catch (e: Exception) {
                fail("Send failed: ${e.message ?: e::class.simpleName}")
                return@launch
            }
            if (accepted.isFailure) {
                val cause = accepted.exceptionOrNull()?.message ?: "outbox rejected the order"
                fail("Send failed: $cause")
                return@launch
            }

            _sendState.value = SendState.Relayed
            val acked = withTimeoutOrNull(ACK_WAIT_MS) {
                // observeAck() is cold; first{} suspends until the vendor ACK arrives.
                // BleMeshSender emits the raw payload ("orderId|ACCEPTED" /
                // "orderId|REJECTED"), so match on the prefix before the LAST '|'
                // plus a known token (orderIds may themselves contain '|').
                // Bare orderId equality is kept as a legacy fallback (treated as accepted).
                sender.observeAck().first { payload ->
                    if (payload == order.orderId) return@first true
                    val sep = payload.lastIndexOf('|')
                    if (sep < 0) return@first false
                    payload.substring(0, sep) == order.orderId &&
                        (payload.substring(sep + 1) == ACK_ACCEPTED ||
                            payload.substring(sep + 1) == ACK_REJECTED)
                }
            }
            if (acked != null) {
                val token = acked.substringAfterLast('|', missingDelimiterValue = "")
                if (token == ACK_REJECTED) {
                    fail("The vendor declined your order.")
                } else {
                    _sendState.value = SendState.Delivered
                }
            }
            // Timeout: deliberately stay Relayed — order is in the mesh,
            // the vendor confirmation just hasn't been observed yet.
        }
    }

    private fun fail(message: String) {
        _errorMessage.value = message
        _sendState.value = SendState.Failed
    }

    companion object {
        /** Simulated/allocated BLE scan window before the first hop. */
        const val SCAN_DELAY_MS = 600L

        /** Max wait for the vendor ACK before settling at Relayed. */
        const val ACK_WAIT_MS = 12_000L

        /**
         * ACK tokens from `VendorPipeline.buildAck` ("orderId|TOKEN").
         * Mirrored here so the customer flavor parses the contract without
         * depending on vendor pipeline types.
         */
        const val ACK_ACCEPTED = "ACCEPTED"
        const val ACK_REJECTED = "REJECTED"
    }
}
