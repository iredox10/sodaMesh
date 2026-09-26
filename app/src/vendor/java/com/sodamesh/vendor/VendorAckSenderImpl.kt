package com.sodamesh.vendor

import com.sodamesh.mesh.VendorPipeline
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Vendor-flavor [VendorAckSender] backed by [VendorPipeline.sendAck].
 *
 * Why this lives in the vendor source set (`app/src/vendor/...`) instead of
 * `main`: the [VendorAckSender] interface is declared in
 * `app/src/vendor/.../VendorRoutes.kt`, so `main` (shared by the customer
 * flavor) cannot reference it without breaking the customer build. Keeping
 * both interface and implementation vendor-only keeps the Hilt graph
 * compile-safe per flavor.
 *
 * Fire-and-forget: routes call this from a `LaunchedEffect`; [VendorPipeline.sendAck]
 * is suspend (GATT notify), so we launch on an injected app-scope instead of
 * blocking the caller. ACK outcomes remain observable via
 * `VendorPipeline.acks` / `observeAcks()`.
 */
@Singleton
class VendorAckSenderImpl @Inject constructor(
    private val vendorPipeline: VendorPipeline,
    @VendorAckScope private val appScope: CoroutineScope,
) : VendorAckSender {

    override fun sendAck(orderId: String, accepted: Boolean) {
        appScope.launch {
            runCatching { vendorPipeline.sendAck(orderId, accepted) }
        }
    }
}

/** Qualifier so the ACK app-scope cannot collide with other CoroutineScope bindings. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VendorAckScope

/**
 * Provides the vendor ACK app-scope. Kept in this file (not AppModule) per the
 * task's file-ownership constraint — AppModule must not be modified.
 */
@Module
@InstallIn(SingletonComponent::class)
object VendorAckScopeModule {

    @Provides
    @Singleton
    @VendorAckScope
    fun provideVendorAckScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
