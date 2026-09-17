package com.sodamesh.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Order status values stored in [OrderEntity.status]. */
object OrderStatus {
    const val NEW = "NEW"
    const val ACCEPTED = "ACCEPTED"
    const val REJECTED = "REJECTED"
}

/**
 * Single row in the `orders` table.
 *
 * [itemsJson] holds the serialized line items (JSON array) so Room stays
 * schema-stable while the catalog model evolves.
 */
@Entity(tableName = "orders")
data class OrderEntity(
    @PrimaryKey val orderId: String,
    val shopId: String,
    val customerName: String,
    val itemsJson: String,
    val totalCents: Long,
    val status: String,
    val ts: Long,
)
