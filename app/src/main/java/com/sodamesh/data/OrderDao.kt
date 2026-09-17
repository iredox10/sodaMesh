package com.sodamesh.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OrderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(order: OrderEntity)

    /** Orders filtered by [OrderStatus], newest first. */
    @Query("SELECT * FROM orders WHERE status = :status ORDER BY ts DESC")
    fun byStatus(status: String): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders ORDER BY ts DESC")
    fun observeAll(): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders WHERE orderId = :orderId LIMIT 1")
    suspend fun getById(orderId: String): OrderEntity?

    @Query("UPDATE orders SET status = :status WHERE orderId = :orderId")
    suspend fun updateStatus(orderId: String, status: String)
}
