package com.sodamesh.mesh.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.sodamesh.mesh.ble.BleAdvertiser.Companion.SERVICE_PARCEL_UUID
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A single BLE peer sighting that passed the [BleScanner] service-UUID
 * filter and the RSSI gate.
 *
 * @param device remote [BluetoothDevice].
 * @param rssi received signal strength in dBm.
 * @param shopId shop id decoded from the advertisement service data, or null
 * when absent/undecodable (e.g. third-party beacons reusing the UUID).
 * @param timestampMillis [System.currentTimeMillis] when the result arrived.
 */
data class ScannedPeer(
    val device: BluetoothDevice,
    val rssi: Int,
    val shopId: String?,
    val timestampMillis: Long = System.currentTimeMillis(),
)

/**
 * BLE central scanner for SodaMesh.
 *
 * - Filters on [MeshConfig.SERVICE_UUID] via [ScanFilter], so only mesh
 *   nodes wake the callback.
 * - Gates on RSSI ([minRssiDbm]) to drop far/weak peers.
 * - Duty-cycles scan windows ([scanWindowMs] on, [scanRestMs] off) to save
 *   battery instead of scanning continuously.
 *
 * Uses `android.bluetooth.le` APIs only. API 26+ (minSdk 26).
 */
@Singleton
class BleScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        /** Default minimum RSSI in dBm; peers weaker than this are dropped. */
        const val DEFAULT_MIN_RSSI_DBM = -85

        /** Default scan-on window per duty cycle. */
        const val DEFAULT_SCAN_WINDOW_MS = 4_000L

        /** Default scan-off rest between windows. */
        const val DEFAULT_SCAN_REST_MS = 6_000L

        internal val SERVICE_UUID: ParcelUuid =
            ParcelUuid(UUID.fromString(com.sodamesh.mesh.MeshConfig.SERVICE_UUID))
    }

    private fun scanFilter(): ScanFilter =
        ScanFilter.Builder()
            .setServiceUuid(SERVICE_UUID)
            .build()

    private fun scanSettings(): ScanSettings =
        ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .build()

    internal fun parseShopId(result: ScanResult): String? {
        val bytes = result.scanRecord?.getServiceData(SERVICE_PARCEL_UUID) ?: return null
        if (bytes.isEmpty()) return null
        return try {
            String(bytes, StandardCharsets.UTF_8).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    internal fun toPeer(result: ScanResult): ScannedPeer =
        ScannedPeer(
            device = result.device,
            rssi = result.rssi,
            shopId = parseShopId(result),
        )

    /**
     * Streams [ScannedPeer] sightings, duty-cycled ([scanWindowMs] ms on,
     * [scanRestMs] ms off) until the flow is cancelled.
     *
     * If scan permission is missing or Bluetooth is off, the flow completes
     * without emitting (never throws SecurityException).
     */
    @SuppressLint("MissingPermission")
    fun scanPeers(
        minRssiDbm: Int = DEFAULT_MIN_RSSI_DBM,
        scanWindowMs: Long = DEFAULT_SCAN_WINDOW_MS,
        scanRestMs: Long = DEFAULT_SCAN_REST_MS,
    ): Flow<ScannedPeer> = callbackFlow {
        if (!BlePermissions.canScan(context)) {
            close()
            return@callbackFlow
        }
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        val scanner = adapter
            ?.takeIf { it.isEnabled }
            ?.bluetoothLeScanner
        if (scanner == null) {
            close()
            return@callbackFlow
        }

        val filters = listOf(scanFilter())
        val settings = scanSettings()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.rssi < minRssiDbm) return
                trySend(toPeer(result))
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                for (result in results) {
                    if (result.rssi < minRssiDbm) continue
                    trySend(toPeer(result))
                }
            }
        }

        val dutyCycle: Job = launch {
            while (isActive) {
                try {
                    scanner.startScan(filters, settings, callback)
                } catch (se: SecurityException) {
                    break
                } catch (iae: IllegalArgumentException) {
                    break
                }
                delay(scanWindowMs)
                try {
                    scanner.stopScan(callback)
                } catch (se: SecurityException) {
                    break
                }
                delay(scanRestMs)
            }
        }

        awaitClose {
            dutyCycle.cancel()
            try {
                scanner.stopScan(callback)
            } catch (ignored: Exception) {
                // BT off / already stopped / missing permission at teardown.
            }
        }
    }

    /**
     * Single duty-cycle sweep that collects peers for [windowMs] then returns
     * them. Convenience wrapper over [scanPeers] for one-shot discovery.
     */
    suspend fun sweepOnce(
        minRssiDbm: Int = DEFAULT_MIN_RSSI_DBM,
        windowMs: Long = DEFAULT_SCAN_WINDOW_MS,
    ): List<ScannedPeer> = coroutineScope {
        val seen = linkedMapOf<String, ScannedPeer>()
        val job = launch {
            scanPeers(
                minRssiDbm = minRssiDbm,
                scanWindowMs = windowMs,
                scanRestMs = Long.MAX_VALUE, // single window, no second cycle
            ).collect { peer ->
                seen[peer.device.address] = peer
            }
        }
        delay(windowMs)
        job.cancel()
        seen.values.toList()
    }
}
