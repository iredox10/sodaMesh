package com.sodamesh.data

import kotlinx.coroutines.flow.Flow

/**
 * Transport boundary between UI/domain and BLE.
 *
 * Customer/vendor ViewModels depend only on this interface; the real BLE
 * implementation (advertising / GATT client, fragmentation, retries) is
 * provided elsewhere so VMs compile and stay unit-testable without radios.
 */
interface MeshSender {
    /**
     * Enqueues already-framed order [bytes] for mesh delivery.
     * Returns success when accepted into the outbox, failure otherwise.
     */
    suspend fun sendOrder(bytes: ByteArray): Result<Unit>

    /**
     * Stream of ACKed orderIds observed from the mesh (vendor confirmations
     * flowing back to the customer, or relay ACKs).
     */
    fun observeAck(): Flow<String>
}
