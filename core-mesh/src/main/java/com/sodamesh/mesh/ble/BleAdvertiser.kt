package com.sodamesh.mesh.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import com.sodamesh.mesh.MeshConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BLE peripheral advertiser for SodaMesh.
 *
 * Advertises [MeshConfig.SERVICE_UUID] with the shopId encoded as the
 * service-data payload so centrals can filter/join without a GATT connection.
 *
 * - API 26+ only (minSdk is 26, no legacy path).
 * - [startAdvertising] is a no-op returning false when BLUETOOTH_ADVERTISE
 *   (API 31+) is missing, Bluetooth is off, or advertising is unsupported.
 */
@Singleton
class BleAdvertiser @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        /** ParcelUuid of [MeshConfig.SERVICE_UUID]. */
        val SERVICE_PARCEL_UUID: ParcelUuid =
            ParcelUuid(UUID.fromString(MeshConfig.SERVICE_UUID))

        /**
         * Cap for the shopId service-data payload. The full 31-byte legacy
         * advertisement (plus scan response overflow) comfortably fits the
         * default 13-byte shopId; longer ids are truncated to stay valid.
         */
        const val MAX_SHOP_ID_BYTES = 20
    }

    private val _isAdvertising = MutableStateFlow(false)

    /** True while an advertisement is active. */
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private var advertiser: BluetoothLeAdvertiser? = null

    private val callback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            _isAdvertising.value = true
        }

        override fun onStartFailure(errorCode: Int) {
            _isAdvertising.value = false
        }
    }

    private fun bluetoothAdvertiser(): BluetoothLeAdvertiser? {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
        val adapter = manager.adapter ?: return null
        if (!adapter.isEnabled) return null
        if (!adapter.isMultipleAdvertisementSupported) return null
        return adapter.bluetoothLeAdvertiser
    }

    /**
     * Starts advertising [SERVICE_PARCEL_UUID] with [shopId] as service data.
     *
     * @return true if the advertise request was handed to the stack
     * (success is reported async via [isAdvertising]); false if permission
     * is missing, Bluetooth is off/unsupported, or the payload is invalid.
     */
    @SuppressLint("MissingPermission")
    fun startAdvertising(shopId: String = MeshConfig.SHOP_ID): Boolean {
        if (_isAdvertising.value) return true
        if (!BlePermissions.canAdvertise(context)) return false

        val advertiser = bluetoothAdvertiser() ?: return false

        val shopBytes = shopId
            .toByteArray(StandardCharsets.UTF_8)
            .take(MAX_SHOP_ID_BYTES)
            .toByteArray()
        if (shopBytes.isEmpty()) return false

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(SERVICE_PARCEL_UUID)
            .addServiceData(SERVICE_PARCEL_UUID, shopBytes)
            .build()

        return try {
            advertiser.startAdvertising(settings, data, callback)
            this.advertiser = advertiser
            true
        } catch (iae: IllegalArgumentException) {
            // Payload too large / malformed AdvertiseData.
            false
        }
    }

    /** Stops an active advertisement. Safe to call when not advertising. */
    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        if (!_isAdvertising.value && advertiser == null) return
        try {
            // stopAdvertising throws when BT is off; still clear state below.
            if (BlePermissions.canAdvertise(context)) {
                advertiser?.stopAdvertising(callback)
            }
        } catch (iae: IllegalArgumentException) {
            // Not currently advertising; fall through to state reset.
        } finally {
            advertiser = null
            _isAdvertising.value = false
        }
    }
}
