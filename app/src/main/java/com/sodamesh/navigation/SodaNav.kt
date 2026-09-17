package com.sodamesh.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sodamesh.common.FlavorConfig

object SodaRoutes {
    const val MENU = "menu"
    const val CART = "cart"
    const val SEND = "send"
    const val VENDOR_HOME = "vendor_home"
    const val ALERTS = "alerts"
}

@Composable
fun SodaNav(
    navController: NavHostController = rememberNavController(),
    isVendor: Boolean = FlavorConfig.isVendor,
    menuScreen: @Composable () -> Unit = { NavPlaceholder(SodaRoutes.MENU) },
    cartScreen: @Composable () -> Unit = { NavPlaceholder(SodaRoutes.CART) },
    sendScreen: @Composable () -> Unit = { NavPlaceholder(SodaRoutes.SEND) },
    vendorHomeScreen: @Composable () -> Unit = { NavPlaceholder(SodaRoutes.VENDOR_HOME) },
    alertsScreen: @Composable () -> Unit = { NavPlaceholder(SodaRoutes.ALERTS) },
) {
    val startDestination = if (isVendor) SodaRoutes.VENDOR_HOME else SodaRoutes.MENU
    NavHost(navController = navController, startDestination = startDestination) {
        if (isVendor) {
            composable(SodaRoutes.VENDOR_HOME) { vendorHomeScreen() }
            composable(SodaRoutes.ALERTS) { alertsScreen() }
        } else {
            composable(SodaRoutes.MENU) { menuScreen() }
            composable(SodaRoutes.CART) { cartScreen() }
            composable(SodaRoutes.SEND) { sendScreen() }
        }
    }
}

@Composable
private fun NavPlaceholder(route: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "$route — screen owned by feature agent")
    }
}
