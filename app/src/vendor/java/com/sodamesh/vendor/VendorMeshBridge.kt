package com.sodamesh.vendor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.sodamesh.mesh.VendorPipeline
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Vendor-side mesh bridge: exposes the main-source-set [VendorPipeline] to the
 * vendor flavor (which `main` cannot reference in reverse) and collects
 * [VendorPipeline.incomingOrders] into [VendorViewModel.onOrderReceived].
 *
 * Pattern mirrors `MeshSenderEntryPoint` in `CustomerRoutes.kt`: the pipeline
 * is a `@Singleton` in the `SingletonComponent`, so it is resolvable via
 * [EntryPointAccessors] at composition time. Missing binding (or non-Hilt
 * preview/test context) leaves the collector idle instead of crashing.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface VendorMeshEntryPoint {
    fun vendorPipeline(): VendorPipeline
}

/**
 * Collects [VendorPipeline.incomingOrders], maps each [com.sodamesh.mesh.VendorInboundOrder]
 * 1:1 to [VendorOrder], and forwards it to [VendorViewModel.onOrderReceived].
 *
 * Idempotent: [VendorViewModel.onOrderReceived] de-dupes by `orderId`, so
 * recomposition / re-collection never duplicates rows.
 */
@Composable
fun CollectVendorIncoming(vm: VendorViewModel) {
    val appContext = LocalContext.current.applicationContext
    val pipeline = remember(appContext) {
        runCatching {
            EntryPointAccessors.fromApplication(
                appContext,
                VendorMeshEntryPoint::class.java,
            ).vendorPipeline()
        }.getOrNull()
    }
    LaunchedEffect(pipeline, vm) {
        pipeline?.incomingOrders?.collect { inbound ->
            vm.onOrderReceived(
                VendorOrder(
                    orderId = inbound.orderId,
                    customerName = inbound.customerName,
                    items = inbound.items.map {
                        VendorOrderItem(
                            name = it.name,
                            qty = it.qty,
                            unitPriceCents = it.unitPriceCents,
                        )
                    },
                    totalCents = inbound.totalCents,
                    receivedAtMillis = inbound.receivedAtMillis,
                ),
            )
        }
    }
}
