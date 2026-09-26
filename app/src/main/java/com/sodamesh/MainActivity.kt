package com.sodamesh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.sodamesh.common.FlavorConfig
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
        if (FlavorConfig.isVendor) {
            MeshService.startVendor(this)
        } else {
            MeshService.startCustomer(this)
        }
        setContent {
            SodaMeshTheme {
                wireNav(FlavorConfig.isVendor)()
            }
        }
    }
}
