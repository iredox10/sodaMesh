package com.sodamesh.mesh

import android.content.Context
import com.sodamesh.MeshRole
import com.sodamesh.mesh.ble.BleAdvertiser
import com.sodamesh.mesh.ble.BleScanner
import com.sodamesh.mesh.transport.GattServerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

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
    @ApplicationContext private val appContext: Context,
) : MeshRole {

    private var gattServer: GattServerManager? = null

    override fun startAdvertising() {
        if (gattServer == null) {
            gattServer = GattServerManager(appContext) {
                // Vendor ingress hook: wire to OrderDao / OrderNotifier when
                // the vendor pipeline lands. Currently no VendorPipeline exists.
            }
        }
        runCatching { gattServer?.start() }
        runCatching { advertiser.startAdvertising() }
    }

    override fun startScanning() {
        // No-op: customer uses the GATT client sender on demand.
        // BleScanner exposes only scanPeers()/sweepOnce(); nothing to start here.
        @Suppress("UNUSED_EXPRESSION")
        scanner
    }

    override fun stop() {
        runCatching { advertiser.stopAdvertising() }
        runCatching { gattServer?.stop() }
        gattServer = null
        // BleScanner needs no stop: scanPeers Flow teardown handles it.
    }
}
