package com.sodamesh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.sodamesh.common.FlavorConfig
import com.sodamesh.perms.PermissionManager
import com.sodamesh.ui.theme.SodaMeshTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host.
 *
 * - Starts [MeshService] in the flavor role ([MeshService.startVendor] /
 *   [MeshService.startCustomer]) in [onCreate].
 * - Hosts the [wireNav] root inside [SodaMeshTheme].
 *
 * No direct BLE code lives here.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // API 34+ forbids starting a connectedDevice FGS without
        // BLUETOOTH_CONNECT already granted. When permissions are still
        // missing, PermissionGate starts the service right after the user
        // grants them; on recreate-with-grants we start immediately.
        if (PermissionManager.missingPerms(this).isEmpty()) {
            if (FlavorConfig.isVendor) {
                MeshService.startVendor(this)
            } else {
                MeshService.startCustomer(this)
            }
        }
        setContent {
            SodaMeshTheme {
                wireNav(FlavorConfig.isVendor)()
            }
        }
    }
}
