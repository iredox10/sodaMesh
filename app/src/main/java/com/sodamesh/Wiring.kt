package com.sodamesh

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.sodamesh.customer.CartRoute
import com.sodamesh.customer.CustomerViewModel
import com.sodamesh.customer.MenuRoute
import com.sodamesh.customer.SendRoute
import com.sodamesh.navigation.SodaNav
import com.sodamesh.perms.PermissionManager
import com.sodamesh.perms.rememberMeshPermissionLauncher
import com.sodamesh.vendor.VendorAlertsRoute
import com.sodamesh.vendor.VendorHomeRoute
import com.sodamesh.vendor.VendorViewModel

/**
 * Nav wiring owned by the infra agent.
 *
 * Returns the root content lambda for the given flavor; [MainActivity] calls
 * the returned lambda inside `setContent`, e.g.:
 *
 * ```
 * setContent { SodaMeshTheme { wireNav(isVendor = FlavorConfig.isVendor)() } }
 * ```
 *
 * The root is gated on [PermissionGate] first, then delegates to the REAL
 * feature route composables (joint compilation with the feature agents):
 *
 * - customer (`com.sodamesh.customer.CustomerRoutes`): [MenuRoute] /
 *   [CartRoute] / [SendRoute] hosted in [SodaNav]; menu→cart→send navigation
 *   lives inside those routes via the hoisted `NavController`.
 * - vendor (`com.sodamesh.vendor.VendorRoutes`): nav-aware [VendorHomeRoute]
 *   (overload taking the `NavController`; resolved by parameter types from the
 *   ViewModel-bound one in `VendorHomeScreen.kt`) + [VendorAlertsRoute].
 *
 * ViewModels are scoped to the host [ComponentActivity] (not the
 * per-destination back-stack entry) so menu/cart/send share one
 * [CustomerViewModel], mirroring `CustomerRoutes.sharedCustomerViewModel`.
 * `hiltViewModel()` is unavailable (`androidx.hilt:hilt-navigation-compose`
 * is not a dependency — no deps added, out of scope); plain [viewModel] with
 * the activity owner is equivalent here since both VMs have no-arg
 * `@Inject` constructors.
 */
fun wireNav(isVendor: Boolean): @Composable () -> Unit = {
    PermissionGate {
        if (isVendor) {
            VendorRoot()
        } else {
            CustomerRoot()
        }
    }
}

@Composable
private fun CustomerRoot() {
    val navController = rememberNavController()
    val vm: CustomerViewModel = viewModel(activityOwner())
    SodaNav(
        navController = navController,
        isVendor = false,
        menuScreen = { MenuRoute(navController = navController, vm = vm) },
        cartScreen = { CartRoute(navController = navController, vm = vm) },
        sendScreen = { SendRoute(navController = navController, vm = vm) },
    )
}

@Composable
private fun VendorRoot() {
    val navController = rememberNavController()
    val vm: VendorViewModel = viewModel(activityOwner())
    SodaNav(
        navController = navController,
        isVendor = true,
        vendorHomeScreen = { VendorHomeRoute(navController = navController, vm = vm) },
        alertsScreen = { VendorAlertsRoute(vm = vm) },
    )
}

/**
 * Activity-scoped [androidx.lifecycle.ViewModelStoreOwner] so customer/vendor
 * flows share one ViewModel across all destinations.
 */
@Composable
private fun activityOwner(): ComponentActivity {
    val context = LocalContext.current
    return (context as? ComponentActivity)
        ?: checkNotNull(LocalViewModelStoreOwner.current as? ComponentActivity) {
            "No ComponentActivity owner for shared ViewModel"
        }
}

/**
 * Blocks [content] until every [PermissionManager.requiredPerms] entry is
 * granted (i.e. [PermissionManager.missingPerms] is empty — the runtime
 * equivalent of "all granted").
 *
 * Shows the per-permission rationale ([PermissionManager.rationaleFor]) plus
 * a grant button driven by [rememberMeshPermissionLauncher]. The missing set
 * is re-queried authoritatively after each launcher result and on every
 * `ON_RESUME` (covers grants made via system Settings).
 */
@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(PermissionManager.missingPerms(context)) }
    val launcher = rememberMeshPermissionLauncher {
        missing = PermissionManager.missingPerms(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                missing = PermissionManager.missingPerms(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (missing.isEmpty()) {
        content()
    } else {
        PermissionGateScreen(
            missing = missing,
            onGrant = { launcher.launch(PermissionManager.requiredPerms().toTypedArray()) },
        )
    }
}

@Composable
private fun PermissionGateScreen(
    missing: List<String>,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Permissions needed",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(12.dp))
        // Distinct by rationale text: several permissions share one
        // explanation (e.g. legacy BLUETOOTH + BLUETOOTH_ADMIN).
        missing.map(PermissionManager::rationaleFor).distinct().forEach { rationale ->
            Text(
                text = rationale,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onGrant) {
            Text("Grant permissions")
        }
    }
}
