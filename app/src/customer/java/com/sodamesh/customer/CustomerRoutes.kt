package com.sodamesh.customer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.sodamesh.data.MeshSender
import com.sodamesh.navigation.SodaRoutes
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point for the transport seam.
 *
 * [CustomerViewModel] takes no constructor deps and exposes [CustomerViewModel.attachSender]
 * instead, so the route layer pulls the [MeshSender] implementation from the
 * SingletonComponent at composition time.
 *
 * NOTE: no `@Provides MeshSender` binding exists yet (see `di/AppModule.kt` —
 * only Gson/dispatchers/DataStore are bound). Until the mesh agent adds one,
 * [EntryPointAccessors] throws, [AttachMeshSender] keeps the VM detached, and
 * sends fail with the VM's clear "sender not attached" message instead of crashing.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MeshSenderEntryPoint {
    fun meshSender(): MeshSender
}

/**
 * Shared VM owner helper.
 *
 * NOTE on `viewModel()` fallback: `app/build.gradle.kts` does NOT include
 * `androidx.hilt:hilt-navigation-compose`, so `hiltViewModel()` is unavailable
 * (no deps added — out of scope). Plain `viewModel()` called inside a
 * `SodaNav` destination would scope to that destination's [NavBackStackEntry],
 * giving each screen its own cart. Scoping to the host [ComponentActivity]
 * instead keeps ONE VM shared across menu/cart/send for the whole customer flow.
 */
@Composable
internal fun sharedCustomerViewModel(): CustomerViewModel {
    val context = LocalContext.current
    val owner = (context as? ComponentActivity)
        ?: checkNotNull(LocalViewModelStoreOwner.current) { "No ViewModelStoreOwner for CustomerViewModel" }
    return viewModel(owner)
}

/**
 * Attaches the Hilt-provided [MeshSender] to [vm] and detaches on dispose.
 * Missing binding (or non-Hilt preview/test context) leaves the VM detached;
 * the VM already fails sends with a clear message in that case.
 */
@Composable
internal fun AttachMeshSender(vm: CustomerViewModel) {
    val appContext = LocalContext.current.applicationContext
    DisposableEffect(appContext, vm) {
        val sender = runCatching {
            EntryPointAccessors
                .fromApplication(appContext, MeshSenderEntryPoint::class.java)
                .meshSender()
        }.getOrNull()
        if (sender != null) vm.attachSender(sender)
        onDispose { vm.detachSender() }
    }
}

/**
 * Customer feature entry point for `wireNav`.
 *
 * Owns the shared [CustomerViewModel] (see [sharedCustomerViewModel]) plus the
 * [MeshSender] attach lifecycle, then renders whichever of [MenuRoute] /
 * [CartRoute] / [SendRoute] matches the current destination. No inner NavHost —
 * routing stays in [com.sodamesh.navigation.SodaNav]; this just dispatches on
 * `currentBackStackEntryAsState()`, so `wireNav` can call it from any/all of
 * the customer `SodaNav` slots with a hoisted `NavController`, or delegate to
 * the individual route fns directly.
 */
@Composable
fun CustomerRoute(
    navController: NavController,
    vm: CustomerViewModel = sharedCustomerViewModel(),
) {
    AttachMeshSender(vm)
    val entry by navController.currentBackStackEntryAsState()
    when (entry?.destination?.route) {
        SodaRoutes.CART -> CartRoute(navController, vm)
        SodaRoutes.SEND -> SendRoute(navController, vm)
        else -> MenuRoute(navController, vm) // MENU + pre-attach null entry (start destination)
    }
}

/** Menu destination: stateless [MenuScreen] wired to [vm] + menu→cart nav. */
@Composable
fun MenuRoute(
    navController: NavController,
    vm: CustomerViewModel,
    modifier: Modifier = Modifier,
) {
    val cart by vm.cart.collectAsStateWithLifecycle()
    MenuScreen(
        menu = vm.menu,
        cart = cart,
        onAdd = vm::add,
        onRemoveOne = vm::removeOne,
        onOpenCart = { navController.navigate(SodaRoutes.CART) },
        modifier = modifier,
    )
}

/** Cart destination: stateless [CartScreen] wired to [vm] + cart→send / back nav. */
@Composable
fun CartRoute(
    navController: NavController,
    vm: CustomerViewModel,
    modifier: Modifier = Modifier,
) {
    val cart by vm.cart.collectAsStateWithLifecycle()
    val customerName by vm.customerName.collectAsStateWithLifecycle()
    CartScreen(
        cart = cart,
        menu = vm.menu,
        customerName = customerName,
        onNameChange = vm::setCustomerName,
        onSetQty = vm::setQty,
        onClear = vm::clearCart,
        onCheckout = { navController.navigate(SodaRoutes.SEND) },
        onBack = { navController.popBackStack() },
        modifier = modifier,
    )
}

/**
 * Send destination: stateless [SendScreen] wired to [vm] send lifecycle.
 *
 * The VM builds the [com.sodamesh.mesh.model.SodaOrder] inside `sendOrder()`,
 * so arriving from checkout with `Idle + lastOrder == null` would leave the
 * "Send order" button disabled with no way forward. The guarded [LaunchedEffect]
 * kicks off that first attempt automatically (idempotent: re-entry guard in
 * `sendOrder()` + `Idle && order == null` condition cover recomposition and
 * back-stack revisits; manual Retry / New order stay user-driven).
 */
@Composable
fun SendRoute(
    navController: NavController,
    vm: CustomerViewModel,
    modifier: Modifier = Modifier,
) {
    val sendState by vm.sendState.collectAsStateWithLifecycle()
    val lastOrder by vm.lastOrder.collectAsStateWithLifecycle()
    val errorMessage by vm.errorMessage.collectAsStateWithLifecycle()

    LaunchedEffect(sendState, lastOrder) {
        if (sendState == SendState.Idle && lastOrder == null) vm.sendOrder()
    }

    SendScreen(
        state = sendState,
        order = lastOrder,
        errorMessage = errorMessage,
        onSend = vm::sendOrder,
        onRetry = vm::retrySend,
        onNewOrder = {
            vm.startNewOrder()
            navController.navigate(SodaRoutes.MENU) {
                popUpTo(SodaRoutes.MENU) { inclusive = false }
            }
        },
        onBack = { navController.popBackStack() },
        modifier = modifier,
    )
}
