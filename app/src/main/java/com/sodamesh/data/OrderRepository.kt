package com.sodamesh.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Persistence boundary for orders. BLE transport lives behind [MeshSender];
 * this repo only touches Room so customer/vendor ViewModels stay testable.
 */
interface OrderRepository {
    /** All orders, newest first. */
    fun observeAll(): Flow<List<OrderEntity>>

    /** Orders filtered by [OrderStatus], newest first. */
    fun observeByStatus(status: String): Flow<List<OrderEntity>>

    /** Insert (or replace) an order created on this device. */
    suspend fun placeLocal(order: OrderEntity)

    /** Insert (or replace) an order received over the mesh. */
    suspend fun incoming(order: OrderEntity)

    /** Transition an order to a new [OrderStatus]. */
    suspend fun setStatus(orderId: String, status: String)
}

@Singleton
class RoomOrderRepository @Inject constructor(
    private val dao: OrderDao,
) : OrderRepository {
    override fun observeAll(): Flow<List<OrderEntity>> = dao.observeAll()

    override fun observeByStatus(status: String): Flow<List<OrderEntity>> =
        dao.byStatus(status)

    override suspend fun placeLocal(order: OrderEntity) {
        dao.insert(order)
    }

    override suspend fun incoming(order: OrderEntity) {
        dao.insert(order)
    }

    override suspend fun setStatus(orderId: String, status: String) {
        dao.updateStatus(orderId, status)
    }
}
