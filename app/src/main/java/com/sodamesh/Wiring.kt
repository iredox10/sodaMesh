package com.sodamesh

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.sodamesh.navigation.SodaNav

/**
 * Nav wiring placeholder owned by the infra agent.
 *
 * Returns the root [SodaNav] content lambda for the given flavor with
 * placeholder screens. Feature agents replace each placeholder with the
 * real screen; MainActivity just calls the returned lambda inside
 * setContent, e.g.:
 *
 * ```
 * setContent { wireNav(isVendor = FlavorConfig.isVendor)() }
 * ```
 *
 * MainActivity is intentionally NOT touched here.
 */
fun wireNav(isVendor: Boolean): @Composable () -> Unit = {
    if (isVendor) {
        SodaNav(
            isVendor = true,
            vendorHomeScreen = { NavTodo("vendor_home") },
            alertsScreen = { NavTodo("alerts") },
        )
    } else {
        SodaNav(
            isVendor = false,
            menuScreen = { NavTodo("menu") },
            cartScreen = { NavTodo("cart") },
            sendScreen = { NavTodo("send") },
        )
    }
}

@Composable
private fun NavTodo(route: String) {
    Text(text = "$route — screen owned by feature agent")
}
