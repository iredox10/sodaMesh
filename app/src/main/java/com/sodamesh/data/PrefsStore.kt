package com.sodamesh.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Typed wrapper around the app Preferences DataStore.
 *
 * Persisted keys: peerId (stable BLE identity), shopId, customerName,
 * vendorMode (true when running the vendor flavor).
 */
@Singleton
class PrefsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val PEER_ID = stringPreferencesKey("peerId")
        val SHOP_ID = stringPreferencesKey("shopId")
        val CUSTOMER_NAME = stringPreferencesKey("customerName")
        val VENDOR_MODE = booleanPreferencesKey("vendorMode")
    }

    val peerId: Flow<String> = dataStore.data.map { it[Keys.PEER_ID].orEmpty() }
    val shopId: Flow<String> = dataStore.data.map { it[Keys.SHOP_ID].orEmpty() }
    val customerName: Flow<String> = dataStore.data.map { it[Keys.CUSTOMER_NAME].orEmpty() }
    val vendorMode: Flow<Boolean> = dataStore.data.map { it[Keys.VENDOR_MODE] ?: false }

    /** Returns the stored peerId, generating + persisting one on first use. */
    suspend fun getOrCreatePeerId(): String {
        val existing = dataStore.data.map { it[Keys.PEER_ID] }.first()
        if (!existing.isNullOrBlank()) return existing
        val fresh = UUID.randomUUID().toString()
        dataStore.edit { it[Keys.PEER_ID] = fresh }
        return fresh
    }

    suspend fun setShopId(shopId: String) {
        dataStore.edit { it[Keys.SHOP_ID] = shopId }
    }

    suspend fun setCustomerName(name: String) {
        dataStore.edit { it[Keys.CUSTOMER_NAME] = name }
    }

    suspend fun setVendorMode(vendor: Boolean) {
        dataStore.edit { it[Keys.VENDOR_MODE] = vendor }
    }
}
