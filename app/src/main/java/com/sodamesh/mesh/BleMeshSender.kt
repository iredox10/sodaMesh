package com.sodamesh.mesh

import android.bluetooth.BluetoothManager
import android.content.Context
import com.sodamesh.data.MeshSender
import com.sodamesh.mesh.MeshConfig
import com.sodamesh.mesh.ble.BlePermissions
import com.sodamesh.mesh.ble.BleScanner
import com.sodamesh.mesh.model.OrderCodec
import com.sodamesh.mesh.store.Outbox
import com.sodamesh.mesh.transport.GattClientManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Customer-side BLE [MeshSender].
 *
 * - [sendOrder] persists the framed order bytes into the [Outbox]
 *   (`peerId = "vendor"`, keyed by the decoded orderId so later ACKs clear
 *   it), does a one-shot [BleScanner.sweepOnce] for the SODA-STORE peer,
 *   then connects + streams the payload via [GattClientManager.writeOrder].
 *   The GATT link is intentionally kept open: the vendor ACK arrives as a
 *   notification on the same connection and is surfaced via [observeAck].
 * - [observeAck] decodes [GattClientManager.acks] (ACK payload = orderId
 *   UTF-8), clears the matching [Outbox] entry, and re-emits the orderId.
 *
 * Missing permissions / Bluetooth-off / no-vendor / link failures all
 * return [Result.failure] with a human-readable message (the customer VM
 * maps these to its Failed state). No UI code lives here.
 */
@Singleton
class BleMeshSender @Inject constructor(
    private val scanner: BleScanner,
    private val gattClient: GattClientManager,
    private val outbox: Outbox,
    @ApplicationContext private val appContext: Context,
) : MeshSender {

    companion object {
        /** Outbox peer bucket for vendor-bound orders. */
        const val VENDOR_PEER_ID = "vendor"

        /** One-shot discovery window for the SODA-STORE peer. */
        const val SCAN_TIMEOUT_MS = 8_000L

        /** Max wait for the GATT link to reach CONNECTED/READY. */
        const val CONNECT_TIMEOUT_MS = 10_000L
    }

    override suspend fun sendOrder(bytes: ByteArray): Result<Unit> {
        if (bytes.isEmpty()) {
            return Result.failure(
                IllegalArgumentException("Order payload is empty — nothing to send."),
            )
        }
        if (!BlePermissions.hasAll(appContext)) {
            return Result.failure(
                SecurityException(
                    "Bluetooth permissions missing — grant Nearby devices access " +
                        "(Location on older Android) and retry.",
                ),
            )
        }
        if (!isBluetoothOn()) {
            return Result.failure(
                IllegalStateException("Bluetooth is off — turn it on to send your order."),
            )
        }

        // Key the outbox entry by orderId so observeAck() can clear it later.
        val queueId = runCatching { OrderCodec.decode(bytes).orderId }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        try {
            outbox.enqueue(VENDOR_PEER_ID, bytes, queueId)
        } catch (e: Exception) {
            return Result.failure(
                IllegalStateException("Could not queue the order: ${e.message}"),
            )
        }

        val peers = try {
            scanner.sweepOnce(windowMs = SCAN_TIMEOUT_MS)
        } catch (se: SecurityException) {
            return Result.failure(
                SecurityException(
                    "Bluetooth scan blocked — grant Nearby devices permission and retry.",
                ),
            )
        } catch (e: Exception) {
            return Result.failure(
                IllegalStateException("Vendor scan failed: ${e.message}"),
            )
        }
        val peer = peers.firstOrNull { it.shopId == MeshConfig.SHOP_ID }
            ?: peers.firstOrNull { it.shopId?.startsWith("SODA-STORE") == true }
            ?: peers.firstOrNull()
            ?: return Result.failure(
                IllegalStateException(
                    "No vendor found — move closer to the SODA-STORE tablet and retry.",
                ),
            )

        val initiated = try {
            gattClient.connect(peer.device)
        } catch (se: SecurityException) {
            return Result.failure(
                SecurityException(
                    "Bluetooth connect blocked — grant Nearby devices permission and retry.",
                ),
            )
        } catch (e: Exception) {
            return Result.failure(
                IllegalStateException("Could not connect to the vendor: ${e.message}"),
            )
        }
        if (!initiated) {
            return Result.failure(
                IllegalStateException("Could not connect to the vendor — retry."),
            )
        }
        val ready = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            gattClient.state.first {
                it == GattClientManager.ConnectionState.READY ||
                    it == GattClientManager.ConnectionState.CONNECTED
            }
        }
        if (ready == null) {
            gattClient.disconnect()
            return Result.failure(
                IllegalStateException("Vendor did not respond — move closer and retry."),
            )
        }

        val written = try {
            gattClient.writeOrder(bytes)
        } catch (se: SecurityException) {
            gattClient.disconnect()
            return Result.failure(
                SecurityException(
                    "Bluetooth write blocked — grant Nearby devices permission and retry.",
                ),
            )
        } catch (e: Exception) {
            gattClient.disconnect()
            return Result.failure(
                IllegalStateException("Order write failed: ${e.message}"),
            )
        }
        if (!written) {
            gattClient.disconnect()
            return Result.failure(
                IllegalStateException(
                    "Order write failed — move closer to the vendor and retry.",
                ),
            )
        }
        // Keep the link open: the vendor ACK arrives as a notification
        // on this connection and is surfaced via observeAck().
        return Result.success(Unit)
    }

    override fun observeAck(): Flow<String> =
        gattClient.acks
            .map { raw -> String(raw, StandardCharsets.UTF_8).trim() }
            .filter { it.isNotEmpty() }
            .onEach { ackedId -> runCatching { outbox.ack(ackedId) } }

    private fun isBluetoothOn(): Boolean =
        try {
            val manager = appContext.getSystemService(BluetoothManager::class.java)
            manager?.adapter?.isEnabled == true
        } catch (_: Exception) {
            false
        }
}
