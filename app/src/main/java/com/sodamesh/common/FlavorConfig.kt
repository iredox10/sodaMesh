package com.sodamesh.common

import com.sodamesh.BuildConfig

object FlavorConfig {
    val isVendor: Boolean get() = BuildConfig.FLAVOR == FLAVOR_VENDOR

    const val FLAVOR_VENDOR = "vendor"
    const val FLAVOR_CUSTOMER = "customer"
}
