package com.sodamesh.mesh.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * BLE permission sets, split by API level.
 *
 * - API 31+ (Android 12+): [BLUETOOTH_SCAN], [BLUETOOTH_ADVERTISE], [BLUETOOTH_CONNECT]
 *   are runtime permissions. Location is NOT required on 31+ as long as the
 *   manifest declares `neverForLocation` for the scan permission.
 * - API <= 30: BLE scanning is gated on [ACCESS_FINE_LOCATION].
 */
object BlePermissions {

    /** Permissions that must be granted at runtime for the current API level. */
    val required: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

    /** True when every permission in [required] is granted. */
    fun hasAll(context: Context): Boolean =
        required.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** True when advertising is allowed (BLUETOOTH_ADVERTISE on 31+, always true <= 30). */
    fun canAdvertise(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** True when scanning is allowed (BLUETOOTH_SCAN on 31+, FINE_LOCATION on <= 30). */
    fun canScan(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }
}
