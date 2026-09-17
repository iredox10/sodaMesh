package com.sodamesh.perms

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat

/**
 * Central BLE/notification permission policy for SodaMesh.
 *
 * Mirrors app/src/main/AndroidManifest.xml. Runtime set depends on API level:
 *  - API 33+: new BT_* runtime perms + POST_NOTIFICATIONS (location not needed;
 *    manifest marks SCAN neverForLocation).
 *  - API 31-32: new BT_* runtime perms (no POST_NOTIFICATIONS yet).
 *  - API <=30: legacy BLUETOOTH/BLUETOOTH_ADMIN + ACCESS_FINE_LOCATION
 *    (matches manifest maxSdkVersion="30" entries).
 */
object PermissionManager {

    const val RATIONALE_SCAN =
        "SodaMesh scans for the nearby store over Bluetooth LE so you can send " +
            "your order without internet."
    const val RATIONALE_ADVERTISE =
        "SodaMesh advertises the store over Bluetooth LE so nearby customer " +
            "phones can find it and place orders."
    const val RATIONALE_CONNECT =
        "SodaMesh connects to nearby devices over Bluetooth LE to exchange " +
            "orders and receipts."
    const val RATIONALE_LOCATION_LEGACY =
        "On Android 11 and below, Bluetooth scanning requires location " +
            "permission to discover the nearby store."
    const val RATIONALE_BLUETOOTH_LEGACY =
        "Bluetooth is needed to discover the store and send your order offline."
    const val RATIONALE_NOTIFICATIONS =
        "SodaMesh plays a loud alert when a new order arrives so the vendor " +
            "never misses it."
    const val RATIONALE_DEFAULT =
        "This permission is needed for SodaMesh to send orders over the " +
            "offline mesh."

    /** Pure/testable branch of the policy; [sdkInt] is e.g. [Build.VERSION.SDK_INT]. */
    fun requiredPermsForSdk(sdkInt: Int): List<String> = when {
        sdkInt >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        sdkInt >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        else -> listOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    /** Runtime permissions required on this device. */
    fun requiredPerms(): List<String> = requiredPermsForSdk(Build.VERSION.SDK_INT)

    /** Subset of [perms] not yet granted. Empty means ready to run the mesh. */
    fun missingPerms(
        context: Context,
        perms: List<String> = requiredPerms(),
    ): List<String> = perms.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    /** True when every entry of a RequestMultiplePermissions result was granted. */
    fun allGranted(results: Map<String, Boolean>): Boolean =
        results.isNotEmpty() && results.values.all { it }

    fun shouldShowRationale(activity: Activity, permission: String): Boolean =
        activity.shouldShowRequestPermissionRationale(permission)

    /** User-facing rationale per permission; show before re-requesting. */
    fun rationaleFor(permission: String): String = when (permission) {
        Manifest.permission.BLUETOOTH_SCAN -> RATIONALE_SCAN
        Manifest.permission.BLUETOOTH_ADVERTISE -> RATIONALE_ADVERTISE
        Manifest.permission.BLUETOOTH_CONNECT -> RATIONALE_CONNECT
        Manifest.permission.ACCESS_FINE_LOCATION -> RATIONALE_LOCATION_LEGACY
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        -> RATIONALE_BLUETOOTH_LEGACY
        Manifest.permission.POST_NOTIFICATIONS -> RATIONALE_NOTIFICATIONS
        else -> RATIONALE_DEFAULT
    }
}

/**
 * Compose-friendly launcher for [PermissionManager.requiredPerms].
 *
 * Usage in a screen:
 * ```
 * val launcher = rememberMeshPermissionLauncher { results ->
 *     if (PermissionManager.allGranted(results)) startMesh() else showRationale()
 * }
 * // on button click:
 * launcher.launch(PermissionManager.requiredPerms().toTypedArray())
 * ```
 */
@Composable
fun rememberMeshPermissionLauncher(
    onResult: (Map<String, Boolean>) -> Unit,
): ActivityResultLauncher<Array<String>> =
    rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
        onResult,
    )
