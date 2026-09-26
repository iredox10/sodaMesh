package com.sodamesh.mesh.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import com.sodamesh.mesh.MeshConfig
import java.util.UUID
import kotlinx.coroutines.delay

/**
 * GATT server side of the SodaMesh transport.
 *
 * Exposes [MeshConfig.SERVICE_UUID] with:
 * - ORDER characteristic (write / write-no-response): remote clients stream
 *   [Fragmenter]-encoded fragments here; each characteristic write carries one
 *   wire fragment. Completed payloads are delivered via [onPacket].
 * - ACK characteristic (notify): server pushes [Fragmenter]-encoded fragments
 *   via [sendAck]; clients subscribe through the CCCD.
 *
 * Only `android.bluetooth` + coroutines are used. The caller owns runtime
 * permissions (BLUETOOTH_CONNECT / BLUETOOTH_ADVERTISE).
 */
@SuppressLint("MissingPermission") // caller must hold BLUETOOTH_CONNECT / BLUETOOTH_ADVERTISE
class GattServerManager(
    private val context: Context,
    private val onPacket: (ByteArray) -> Unit,
) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString(MeshConfig.SERVICE_UUID)
        val ORDER_CHAR_UUID: UUID = UUID.fromString(MeshConfig.ORDER_CHAR_UUID)
        val ACK_CHAR_UUID: UUID = UUID.fromString(MeshConfig.ACK_CHAR_UUID)
        val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Delay between consecutive ACK notify writes. */
        const val NOTIFY_DELAY_MS = 20L
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val reassembler = Reassembler()

    private var server: BluetoothGattServer? = null
    private var orderChar: BluetoothGattCharacteristic? = null
    private var ackChar: BluetoothGattCharacteristic? = null

    /** Devices that subscribed to ACK notifications via the CCCD. */
    private val subscribers = mutableSetOf<BluetoothDevice>()

    @Volatile
    var isRunning: Boolean = false
        private set

    @Suppress("DEPRECATION") // classic APIs: work on minSdk 26 .. 34 without version branches
    private val callback = object : BluetoothGattServerCallback() {

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid == ORDER_CHAR_UUID) {
                val completed = try {
                    reassembler.feed(value)
                } catch (_: Exception) {
                    null
                }
                if (completed != null) {
                    try {
                        onPacket(completed)
                    } catch (_: Exception) {
                        // Never let client code kill the GATT callback thread.
                    }
                }
            }
            if (responseNeeded) {
                try {
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                } catch (_: Exception) {
                    // Best effort.
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                synchronized(subscribers) {
                    if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                        subscribers.add(device)
                    } else {
                        subscribers.remove(device)
                    }
                }
            }
            if (responseNeeded) {
                try {
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                } catch (_: Exception) {
                    // Best effort.
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val value = if (descriptor.uuid == CCCD_UUID) {
                val subscribed = synchronized(subscribers) { subscribers.contains(device) }
                if (subscribed) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
            } else {
                byteArrayOf()
            }
            try {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            } catch (_: Exception) {
                // Best effort.
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                synchronized(subscribers) { subscribers.remove(device) }
            }
        }
    }

    /**
     * Opens the GATT server and publishes the Mesh service.
     * @return false when Bluetooth is unavailable.
     */
    @Suppress("DEPRECATION")
    fun start(): Boolean {
        if (isRunning) return true
        val manager = bluetoothManager ?: return false
        val adapter = manager.adapter ?: return false
        if (!adapter.isEnabled) return false

        val gattServer = manager.openGattServer(context, callback) ?: return false

        val order = BluetoothGattCharacteristic(
            ORDER_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val ack = BluetoothGattCharacteristic(
            ACK_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        ack.addDescriptor(cccd)

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(order)
        service.addCharacteristic(ack)
        if (!gattServer.addService(service)) {
            try {
                gattServer.close()
            } catch (_: Exception) {
                // Best effort.
            }
            return false
        }

        server = gattServer
        orderChar = order
        ackChar = ack
        isRunning = true
        return true
    }

    /**
     * Fragments [payload] and notifies every subscribed device, one wire
     * fragment per notify with a 20 ms gap.
     * @return false when the server is down or nobody is subscribed.
     */
    @Suppress("DEPRECATION")
    suspend fun sendAck(
        payload: ByteArray,
        fragId: Int = Fragmenter.nextFragId(),
    ): Boolean {
        val gattServer = server ?: return false
        val characteristic = ackChar ?: return false
        val targets = synchronized(subscribers) { subscribers.toList() }
        if (targets.isEmpty()) return false
        val fragments = try {
            Fragmenter.split(fragId, payload).map(Fragmenter::encode)
        } catch (_: IllegalArgumentException) {
            return false
        }
        for (fragment in fragments) {
            characteristic.value = fragment
            for (device in targets) {
                try {
                    gattServer.notifyCharacteristicChanged(device, characteristic, false)
                } catch (_: Exception) {
                    // Drop failed targets, keep going.
                }
            }
            delay(NOTIFY_DELAY_MS)
        }
        return true
    }

    /** Shuts the GATT server down and clears subscriptions. */
    fun stop() {
        isRunning = false
        synchronized(subscribers) { subscribers.clear() }
        try {
            server?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        server = null
        orderChar = null
        ackChar = null
    }
}
