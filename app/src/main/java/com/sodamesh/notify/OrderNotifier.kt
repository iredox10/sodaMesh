package com.sodamesh.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sodamesh.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loud order alerts for the vendor flavor + keep-alive builder for [com.sodamesh.MeshService].
 *
 * - Channel [CHANNEL_ORDERS] ("soda_orders"): IMPORTANCE_HIGH, default
 *   notification sound, vibration pattern, lights, public on lockscreen.
 * - [notify] posts a heads-up alert per order; [cancelOnAccept] withdraws it
 *   when the vendor accepts (call from the alerts screen / accept action).
 */
@Singleton
class OrderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        const val CHANNEL_ORDERS = "soda_orders"
        const val CHANNEL_KEEP_ALIVE = "soda_mesh_service"
        const val ACTION_ACCEPT_ORDER = "com.sodamesh.action.ACCEPT_ORDER"
        const val EXTRA_ORDER_ID = "extra_order_id"

        private val VIBRATE_PATTERN = longArrayOf(0L, 500L, 200L, 500L)
    }

    private val manager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val defaultSound: Uri
        get() = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    /** Creates both channels on API 26+; no-op otherwise and safe to call twice. */
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val audio = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()
        val orders = NotificationChannel(
            CHANNEL_ORDERS,
            "Soda Orders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Loud alerts for incoming mesh orders"
            enableVibration(true)
            vibrationPattern = VIBRATE_PATTERN
            setSound(defaultSound, audio)
            enableLights(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        val keepAlive = NotificationChannel(
            CHANNEL_KEEP_ALIVE,
            "Mesh Service",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the offline mesh running"
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannels(listOf(orders, keepAlive))
    }

    /**
     * Loud heads-up alert for one order. Stable id per [orderId], so repeats
     * update instead of stacking. No-op when POST_NOTIFICATIONS is denied
     * on API 33+.
     */
    fun notify(orderId: String, title: String, body: String) {
        ensureChannels()
        if (!canPostAlert()) return

        val contentIntent = pendingFor(
            Intent(context, MainActivity::class.java),
            requestCode = notificationId(orderId),
        )
        val acceptIntent = pendingFor(
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_ACCEPT_ORDER)
                .putExtra(EXTRA_ORDER_ID, orderId),
            requestCode = notificationId(orderId) + 1,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ORDERS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(defaultSound)
            .setVibrate(VIBRATE_PATTERN)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_menu_send,
                "Accept",
                acceptIntent,
            )
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(orderId), notification)
    }

    /** Withdraws the alert for [orderId]; call when the vendor accepts it. */
    fun cancelOnAccept(orderId: String) {
        manager.cancel(notificationId(orderId))
    }

    /** Silent ongoing notification used by MeshService.startForeground. */
    fun buildKeepAlive(status: String = "Mesh running — listening for orders"): Notification {
        ensureChannels()
        val contentIntent = pendingFor(
            Intent(context, MainActivity::class.java),
            requestCode = 0,
        )
        return NotificationCompat.Builder(context, CHANNEL_KEEP_ALIVE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("SodaMesh")
            .setContentText(status)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(contentIntent)
            .build()
    }

    /** Stable notification id per order. */
    fun notificationId(orderId: String): Int = orderId.hashCode()

    private fun canPostAlert(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun pendingFor(intent: Intent, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
