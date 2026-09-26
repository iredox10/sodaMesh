package com.sodamesh.vendor

import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.Composable
import com.sodamesh.NavRoot
import com.sodamesh.navigation.SodaNav
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/**
 * Vendor-flavor [NavRoot]: vendor home + alerts navigation with the
 * always-on inbound-order collector ([CollectVendorIncoming]) so pending
 * orders surface from any destination.
 *
 * Lives in the vendor source set because it references vendor-only types;
 * `main` sees only [NavRoot].
 */
class VendorNavRoot @Inject constructor() : NavRoot {
    @Composable
    override fun Root() {
        val navController = rememberNavController()
        val vm = sharedVendorViewModel()
        // Always-on collector: inbound mesh orders reach the VM from ANY
        // destination (auto-nav to alerts keys off pendingCount, which would
        // never grow if collection only ran while the alerts screen was up).
        CollectVendorIncoming(vm = vm)
        SodaNav(
            navController = navController,
            isVendor = true,
            vendorHomeScreen = { VendorHomeRoute(navController = navController, vm = vm) },
            alertsScreen = { VendorAlertsRoute(vm = vm) },
        )
    }
}

/** Binds the vendor nav root for the flavor Hilt graph. */
@Module
@InstallIn(SingletonComponent::class)
abstract class VendorNavModule {

    @Binds
    abstract fun bindNavRoot(impl: VendorNavRoot): NavRoot
}
