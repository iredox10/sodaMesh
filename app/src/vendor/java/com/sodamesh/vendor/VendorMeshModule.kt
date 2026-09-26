package com.sodamesh.vendor

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Vendor-only Hilt bindings.
 *
 * Lives in the vendor source set so the customer flavor never sees
 * [VendorAckSender]; this satisfies the existing `VendorAckEntryPoint`
 * (declared in VendorRoutes.kt) with no changes needed there — the
 * `resolveVendorAckSender` global -> EntryPoint chain now resolves.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class VendorMeshModule {

    @Binds
    abstract fun bindVendorAckSender(impl: VendorAckSenderImpl): VendorAckSender
}
