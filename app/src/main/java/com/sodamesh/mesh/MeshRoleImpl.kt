package com.sodamesh.mesh

import android.content.Context
import android.util.Log
import com.sodamesh.MeshRole
import com.sodamesh.mesh.ble.BleAdvertiser
import com.sodamesh.mesh.ble.BleScanner
import com.sodamesh.mesh.router.MessageRouter
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
    private val router: MessageRouter,
    @ApplicationContext private val appContext: Context,
) : MeshRole {

    private var gattServer: GattServerManager? = null

    private companion object {
        const val TAG = "MeshRoleImpl"
    }

    override fun startAdvertising() {
        if (gattServer == null) {
            // Ingress: reassembled GATT bytes are MeshPacket envelopes
            // (BleMeshSender wraps orders before writing). Decap via the
            // router first — dedup + relay bookkeeping run there — then feed
            // the order payload to the pipeline. Legacy raw payloads
            // (pre-envelope senders) pass through untouched.
            val server = GattServerManager(appContext, onPacket = { bytes ->
                val payload = router.ingressPayload(bytes)
                if (payload != null) pipeline.handlePacket(payload)
            })
            gattServer = server
            runCatching { pipeline.bindGatt(server) }
        }
        val serverUp = runCatching { gattServer?.start() }.getOrDefault(false)
        val advertiseUp = runCatching { advertiser.startAdvertising() }.getOrDefault(false)
        if (!serverUp || !advertiseUp) {
            // Diagnose the "customer can't find vendor" class of failures:
            // Bluetooth off, missing permission, chipset refusal, etc.
            Log.w(TAG, "vendor mesh start incomplete: gattServer=$serverUp advertise=$advertiseUp")
        }
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
