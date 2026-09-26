package com.sodamesh.vendor

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.sodamesh.MeshService
import com.sodamesh.navigation.SodaRoutes
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Vendor nav routes. Scope: wiring only — no VM/screen/gradle changes.
 *
 * - [VendorHomeRoute] (this file) is the nav-aware overload of the
 *   ViewModel-bound `VendorHomeRoute` in `VendorHomeScreen.kt`. Overload is
 *   resolved by the first parameter (`NavHostController` here vs
 *   `VendorViewModel` there); existing call sites are unaffected.
 * - [VendorAlertsRoute] hosts the latest PENDING order alert plus the full
 *   order list, and forwards accept/reject ACKs to the mesh layer.
 */

// ---- ACK transport seam ---------------------------------------------------

/**
 * Transport seam for vendor accept/reject ACKs.
 *
 * The ViewModel stays transport-free: [VendorViewModel.acceptOrder] /
 * [rejectOrder] only update local state and publish [VendorViewModel.lastAck].
 * Whoever owns the mesh stack implements this and either sets
 * [vendorAckSender] or binds it via [VendorAckEntryPoint]; the routes forward
 * each ACK exactly once (see [ForwardVendorAcks]).
 */
interface VendorAckSender {
    fun sendAck(orderId: String, accepted: Boolean)
}

/**
 * Settable by the mesh agent at startup (e.g. after the GATT/relay stack is
 * ready). Preferred over the EntryPoint lookup when the sender instance is
 * created outside Hilt.
 */
@Volatile
var vendorAckSender: VendorAckSender? = null

/** Hilt accessor so the sender can also be provided via DI. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface VendorAckEntryPoint {
    fun vendorAckSender(): VendorAckSender
}

/**
 * Resolution order: explicit route param -> [vendorAckSender] global ->
 * Hilt [VendorAckEntryPoint]. Null when nothing is attached yet (decisions
 * still apply locally; the ACK is just not fanned out).
 */
fun resolveVendorAckSender(context: Context): VendorAckSender? {
    vendorAckSender?.let { return it }
    return runCatching {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            VendorAckEntryPoint::class.java,
        ).vendorAckSender()
    }.getOrNull()
}

/**
 * Observes [VendorViewModel.lastAck], forwards it through the resolved
 * [VendorAckSender], then clears it via [VendorViewModel.clearAck] so each
 * decision is sent exactly once.
 */
@Composable
internal fun ForwardVendorAcks(
    vm: VendorViewModel,
    ackSender: VendorAckSender? = null,
) {
    val context = LocalContext.current.applicationContext
    val lastAck by vm.lastAck.collectAsStateWithLifecycle()
    LaunchedEffect(lastAck) {
        val ack = lastAck ?: return@LaunchedEffect
        val (orderId, decision) = ack
        val sender = ackSender ?: resolveVendorAckSender(context)
        runCatching {
            sender?.sendAck(orderId, accepted = decision == VendorOrderStatus.ACCEPTED)
        }
        vm.clearAck()
    }
}

// ---- Routes ---------------------------------------------------------------

/**
 * Activity-scoped [androidx.lifecycle.ViewModelStoreOwner] so vendor
 * destinations share one ViewModel across home/alerts (mirrors the customer
 * pattern in CustomerRoutes.kt).
 */
@Composable
internal fun sharedVendorViewModel(): VendorViewModel {
    val context = LocalContext.current
    val owner = (context as? ComponentActivity)
        ?: checkNotNull(LocalViewModelStoreOwner.current) { "No ViewModelStoreOwner for VendorViewModel" }
    return viewModel(owner)
}

/**
 * Nav-aware vendor home.
 *
 * @param navController host nav controller; used for the default
 *   [onViewAlerts]/[onViewOrders] navigation to [SodaRoutes.ALERTS].
 * @param vm Hilt-injected [VendorViewModel] passed in by the caller.
 * @param autoNavigateToAlerts when true, navigates `vendor_home -> alerts`
 *   (single-top) whenever a pending order exists while this route is the
 *   current destination.
 * @param onToggleAdvertise defaults to starting/stopping the foreground
 *   [MeshService] (vendor/advertiser mode) in lockstep with
 *   [VendorViewModel.toggleAdvertising].
 */
@Composable
fun VendorHomeRoute(
    navController: NavHostController,
    vm: VendorViewModel,
    modifier: Modifier = Modifier,
    autoNavigateToAlerts: Boolean = true,
    onViewAlerts: () -> Unit = {
        navController.navigate(SodaRoutes.ALERTS) { launchSingleTop = true }
    },
    onViewOrders: () -> Unit = onViewAlerts,
    onToggleAdvertise: (() -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val isAdvertising by vm.isAdvertising.collectAsStateWithLifecycle()
    val orders by vm.orders.collectAsStateWithLifecycle()
    val pendingCount by vm.pendingCount.collectAsStateWithLifecycle()

    val toggleAdvertise: () -> Unit = onToggleAdvertise ?: {
        if (isAdvertising) {
            MeshService.stop(context)
        } else {
            MeshService.startVendor(context)
        }
        vm.toggleAdvertising()
    }

    LaunchedEffect(pendingCount, autoNavigateToAlerts) {
        if (!autoNavigateToAlerts || pendingCount <= 0) return@LaunchedEffect
        if (navController.currentDestination?.route == SodaRoutes.VENDOR_HOME) {
            onViewAlerts()
        }
    }

    ForwardVendorAcks(vm = vm)

    CollectVendorIncoming(vm = vm)

    VendorHomeScreen(
        isAdvertising = isAdvertising,
        orderCount = orders.size,
        pendingCount = pendingCount,
        onToggleAdvertise = toggleAdvertise,
        onViewOrders = onViewOrders,
        onViewAlerts = onViewAlerts,
        modifier = modifier,
    )
}

/**
 * Alerts destination: latest PENDING order as a full-screen alert plus the
 * full order list.
 *
 * @param onAlertFired side-effect hook fired once per incoming order id
 *   (via [OrderAlertScreen]); the host wires actual sound playback and
 *   vibration here so the screens stay testable and permission-free.
 * @param ackSender explicit [VendorAckSender]; defaults to the
 *   [resolveVendorAckSender] chain (global -> Hilt EntryPoint).
 * @param showOrderList when true, the [OrderListScreen] list is shown below
 *   the alert (or full-screen when nothing is pending).
 */
@Composable
fun VendorAlertsRoute(
    vm: VendorViewModel,
    modifier: Modifier = Modifier,
    onAlertFired: (orderId: String) -> Unit = {},
    ackSender: VendorAckSender? = null,
    showOrderList: Boolean = true,
    onOrderClick: (orderId: String) -> Unit = {},
) {
    val orders by vm.orders.collectAsStateWithLifecycle()

    ForwardVendorAcks(vm = vm, ackSender = ackSender)

    CollectVendorIncoming(vm = vm)

    val latestPending = orders
        .filter { it.status == VendorOrderStatus.PENDING }
        .maxByOrNull { it.receivedAtMillis }

    if (latestPending != null && showOrderList) {
        Column(modifier = modifier) {
            OrderAlertScreen(
                order = latestPending,
                onAccept = vm::acceptOrder,
                onReject = vm::rejectOrder,
                onAlertFired = onAlertFired,
                modifier = Modifier.weight(1f),
            )
            OrderListScreen(
                orders = orders,
                onAccept = vm::acceptOrder,
                onReject = vm::rejectOrder,
                onOrderClick = onOrderClick,
                modifier = Modifier.weight(1f),
            )
        }
    } else if (latestPending != null) {
        OrderAlertScreen(
            order = latestPending,
            onAccept = vm::acceptOrder,
            onReject = vm::rejectOrder,
            onAlertFired = onAlertFired,
            modifier = modifier,
        )
    } else if (showOrderList) {
        OrderListScreen(
            orders = orders,
            onAccept = vm::acceptOrder,
            onReject = vm::rejectOrder,
            onOrderClick = onOrderClick,
            modifier = modifier,
        )
    } else {
        OrderAlertEmpty(modifier = modifier)
    }
}
