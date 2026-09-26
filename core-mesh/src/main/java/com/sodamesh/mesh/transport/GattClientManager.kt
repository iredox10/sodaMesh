package com.sodamesh.mesh.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.sodamesh.mesh.MeshConfig
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * GATT client side of the SodaMesh transport.
 *
 * Flow: [connect] a device -> services are discovered automatically -> ACK
 * notifications are enabled automatically ([ConnectionState.READY]) ->
 * [writeOrder] streams [Fragmenter]-encoded fragments to the ORDER
 * characteristic, one fragment per write with a 20 ms gap.
 *
 * Reassembled ACK payloads are exposed as [acks]. Only `android.bluetooth` +
 * coroutines are used. The caller owns runtime permissions (BLUETOOTH_CONNECT).
 */
@SuppressLint("MissingPermission") // caller must hold BLUETOOTH_CONNECT
class GattClientManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    /** Lifecycle of the single active connection. */
    enum class ConnectionState {
        IDLE,
        CONNECTING,
        CONNECTED,
        READY,
        DISCONNECTED,
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString(MeshConfig.SERVICE_UUID)
        val ORDER_CHAR_UUID: UUID = UUID.fromString(MeshConfig.ORDER_CHAR_UUID)
        val ACK_CHAR_UUID: UUID = UUID.fromString(MeshConfig.ACK_CHAR_UUID)
        val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Delay between consecutive ORDER writes. */
        const val WRITE_DELAY_MS = 20L

        /** ATT overhead: one write carries at most (mtu - 3) bytes. */
        const val ATT_OVERHEAD = 3

        const val DEFAULT_MTU = 23
        const val REQUESTED_MTU = 517
        const val WRITE_TIMEOUT_MS = 5_000L

        /** Fallback if the stack never answers the MTU request. */
        const val MTU_FALLBACK_MS = 2_000L
    }

    private val _state = MutableStateFlow(ConnectionState.IDLE)
    /** Current connection state. */
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _acks = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    /** Reassembled ACK payloads from the server. */
    val acks: SharedFlow<ByteArray> = _acks.asSharedFlow()

    private val reassembler = Reassembler()
    private val writeMutex = Mutex()

    /** True once discoverServices() has been issued for the current link. */
    @Volatile
    private var discoveryStarted: Boolean = false

    private var gatt: BluetoothGatt? = null
    private var orderChar: BluetoothGattCharacteristic? = null
    private var ackChar: BluetoothGattCharacteristic? = null

    @Volatile
    private var mtu: Int = DEFAULT_MTU

    /** Completed by [callback.onCharacteristicWrite]; guarded by [writeMutex]. */
    @Volatile
    private var pendingWrite: CompletableDeferred<Int>? = null

    @Suppress("DEPRECATION") // classic APIs: work on minSdk 26 .. 34 without version branches
    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _state.value = ConnectionState.CONNECTED
                // ATT allows only ONE pending request: request MTU first and
                // chain discovery from onMtuChanged. Issuing both back-to-back
                // makes many OEM stacks (Samsung/Xiaomi/Pixel) fail discovery
                // with status 133.
                discoveryStarted = false
                val mtuRequested = try {
                    g.requestMtu(REQUESTED_MTU)
                } catch (_: Exception) {
                    false
                }
                if (!mtuRequested) {
                    startDiscovery(g)
                } else {
                    // Safety net for stacks that never invoke onMtuChanged.
                    scope.launch {
                        delay(MTU_FALLBACK_MS)
                        if (_state.value == ConnectionState.CONNECTED && !discoveryStarted) {
                            startDiscovery(g)
                        }
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _state.value = ConnectionState.DISCONNECTED
                pendingWrite?.complete(BluetoothGatt.GATT_FAILURE)
                pendingWrite = null
                orderChar = null
                ackChar = null
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newMtu > mtu) {
                mtu = newMtu
            }
            // Continue the connect chain whatever the MTU outcome was —
            // DEFAULT_MTU still works.
            startDiscovery(g)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnect()
                return
            }
            val service = g.getService(SERVICE_UUID) ?: run {
                disconnect()
                return
            }
            val order = service.getCharacteristic(ORDER_CHAR_UUID)
            val ack = service.getCharacteristic(ACK_CHAR_UUID)
            if (order == null || ack == null) {
                disconnect()
                return
            }
            orderChar = order
            ackChar = ack
            try {
                g.setCharacteristicNotification(ack, true)
            } catch (_: Exception) {
                disconnect()
                return
            }
            val cccd = ack.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                _state.value = ConnectionState.READY
                return
            }
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            try {
                g.writeDescriptor(cccd)
            } catch (_: Exception) {
                disconnect()
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid == CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                _state.value = ConnectionState.READY
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != ACK_CHAR_UUID) return
            val completed = try {
                reassembler.feed(characteristic.value)
            } catch (_: Exception) {
                null
            }
            if (completed != null) {
                // Callback thread must not block: buffer, else hand off to scope.
                if (!_acks.tryEmit(completed)) {
                    scope.launch { _acks.emit(completed) }
                }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            pendingWrite?.complete(status)
        }
    }

    private fun startDiscovery(g: BluetoothGatt) {
        if (discoveryStarted) return
        discoveryStarted = true
        try {
            if (!g.discoverServices()) disconnect()
        } catch (_: Exception) {
            disconnect()
        }
    }

    /**
     * Connects to [device] over LE (previous connection, if any, is closed).
     * Service discovery + ACK subscription follow automatically.
     * @return false when the connection could not be initiated.
     */
    fun connect(device: BluetoothDevice): Boolean {
        disconnect()
        _state.value = ConnectionState.CONNECTING
        val g = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: Exception) {
            null
        } ?: run {
            _state.value = ConnectionState.DISCONNECTED
            return false
        }
        gatt = g
        return true
    }

    /**
     * Fragments [payload] (chunk size derived from the negotiated MTU, capped
     * at [Fragmenter.MAX_PAYLOAD_PER_FRAGMENT]) and writes one wire fragment
     * per ORDER write with a 20 ms gap. Serialized via [writeMutex].
     * @return false when not connected/subscribed or a write failed.
     */
    @Suppress("DEPRECATION")
    suspend fun writeOrder(
        payload: ByteArray,
        fragId: Int = Fragmenter.nextFragId(),
    ): Boolean {
        val g = gatt ?: return false
        val order = orderChar ?: return false
        if (_state.value != ConnectionState.READY && _state.value != ConnectionState.CONNECTED) {
            return false
        }
        val chunkSize = (mtu - ATT_OVERHEAD - Fragmenter.HEADER_SIZE)
            .coerceIn(1, Fragmenter.MAX_PAYLOAD_PER_FRAGMENT)
        val fragments = try {
            Fragmenter.split(fragId, payload, chunkSize).map(Fragmenter::encode)
        } catch (_: IllegalArgumentException) {
            return false
        }
        writeMutex.withLock {
            for (fragment in fragments) {
                if (!writeOne(g, order, fragment)) return false
                delay(WRITE_DELAY_MS)
            }
        }
        return true
    }

    @Suppress("DEPRECATION")
    private suspend fun writeOne(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        wire: ByteArray,
    ): Boolean {
        val done = CompletableDeferred<Int>()
        pendingWrite = done
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = wire
        val accepted = try {
            g.writeCharacteristic(characteristic)
        } catch (_: Exception) {
            false
        }
        if (!accepted) {
            pendingWrite = null
            return false
        }
        val status = withTimeoutOrNull(WRITE_TIMEOUT_MS) { done.await() }
        pendingWrite = null
        return status == BluetoothGatt.GATT_SUCCESS
    }

    /** Disconnects and releases the GATT client. */
    fun disconnect() {
        pendingWrite?.complete(BluetoothGatt.GATT_FAILURE)
        pendingWrite = null
        try {
            gatt?.disconnect()
        } catch (_: Exception) {
            // Best effort.
        }
        try {
            gatt?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        gatt = null
        orderChar = null
        ackChar = null
        mtu = DEFAULT_MTU
        discoveryStarted = false
        if (_state.value != ConnectionState.IDLE) {
            _state.value = ConnectionState.DISCONNECTED
        }
    }
}
