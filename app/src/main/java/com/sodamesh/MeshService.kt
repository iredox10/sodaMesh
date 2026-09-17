package com.sodamesh

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sodamesh.notify.OrderNotifier

/**
 * Role callbacks the service drives. Deliberately implementation-free:
 * the BLE owning agent provides the GATT server/scanner impl and hands it
 * to the service (via bind + [MeshService.role] or Hilt entry point).
 *
 * - Vendor mode  -> [startAdvertising] (GATT server + advertiser).
 * - Customer mode -> [startScanning] (scanner + GATT client).
 */
interface MeshRole {
    fun startAdvertising()
    fun startScanning()
    fun stop()
}

/**
 * Foreground service (type `connectedDevice`, see manifest) that keeps the
 * mesh alive. Mode is chosen at start time:
 *
 * - Vendor   (store tablet): [startVendor]   -> [MeshRole.startAdvertising]
 * - Customer (buyer phone):  [startCustomer] -> [MeshRole.startScanning]
 *
 * No BLE code lives here — this class only owns the foreground lifecycle
 * and delegates to the injected [role].
 */
class MeshService : Service() {

    /** Set by the owning agent after bind; no default impl on purpose. */
    var role: MeshRole? = null

    private lateinit var notifier: OrderNotifier
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): MeshService = this@MeshService
    }

    companion object {
        const val ACTION_START_VENDOR = "com.sodamesh.action.START_VENDOR"
        const val ACTION_START_CUSTOMER = "com.sodamesh.action.START_CUSTOMER"
        const val ACTION_STOP = "com.sodamesh.action.STOP"
        const val EXTRA_IS_VENDOR = "extra_is_vendor"

        private const val KEEP_ALIVE_ID = 1001

        /** Start (or re-target) the service in vendor/advertiser mode. */
        fun startVendor(context: Context) {
            val intent = Intent(context, MeshService::class.java)
                .setAction(ACTION_START_VENDOR)
                .putExtra(EXTRA_IS_VENDOR, true)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Start (or re-target) the service in customer/scanner mode. */
        fun startCustomer(context: Context) {
            val intent = Intent(context, MeshService::class.java)
                .setAction(ACTION_START_CUSTOMER)
                .putExtra(EXTRA_IS_VENDOR, false)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifier = OrderNotifier(this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val foregroundType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
        ServiceCompat.startForeground(
            this,
            KEEP_ALIVE_ID,
            notifier.buildKeepAlive(),
            foregroundType,
        )

        when (intent?.action) {
            ACTION_START_VENDOR -> role?.startAdvertising()
            ACTION_START_CUSTOMER -> role?.startScanning()
            ACTION_STOP -> stopSelf()
            else -> if (intent?.getBooleanExtra(EXTRA_IS_VENDOR, false) == true) {
                role?.startAdvertising()
            } else {
                role?.startScanning()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { role?.stop() }
        role = null
        super.onDestroy()
    }
}
