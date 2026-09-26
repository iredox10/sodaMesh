package com.sodamesh.mesh

import android.content.Context
import com.sodamesh.MeshRole
import com.sodamesh.mesh.ble.BleAdvertiser
import com.sodamesh.mesh.ble.BleScanner
import com.sodamesh.mesh.transport.GattServerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Vendor-side [MeshRole].
 *
 * - [startAdvertising] brings up the GATT server + BLE advertiser.
 * - [startScanning] is a no-op: the customer sends on demand via the
 *   GATT client sender (BleScanner.scanPeers is a cold Flow collected
 *   only for one-shot discovery, so no continuous scan is owned here).
 * - [stop] tears everything down. All calls are null-safe / best-effort.
 */
@Singleton
class MeshRoleImpl @Inject constructor(
    private val advertiser: BleAdvertiser,
    private val scanner: BleScanner,
    private val pipeline: VendorPipeline,
    @ApplicationContext private val appContext: Context,
) : MeshRole {

    private var gattServer: GattServerManager? = null

    override fun startAdvertising() {
        if (gattServer == null) {
            val server = GattServerManager(appContext, onPacket = pipeline::handlePacket)
            gattServer = server
            runCatching { pipeline.bindGatt(server) }
        }
        runCatching { gattServer?.start() }
        runCatching { advertiser.startAdvertising() }
        runCatching { pipeline.start(CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
    }

    override fun startScanning() {
        // No-op: customer uses the GATT client sender on demand.
        // BleScanner exposes only scanPeers()/sweepOnce(); nothing to start here.
        @Suppress("UNUSED_EXPRESSION")
        scanner
    }

    override fun stop() {
        runCatching { pipeline.stop() }
        runCatching { advertiser.stopAdvertising() }
        runCatching { gattServer?.stop() }
        runCatching { pipeline.bindGatt(null) }
        gattServer = null
        // BleScanner needs no stop: scanPeers Flow teardown handles it.
    }
}
