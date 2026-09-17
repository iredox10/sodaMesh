package com.sodamesh.mesh.model

import java.util.UUID

/** A single line item: [drinkId] must exist in [Drink.MENU], [qty] in 1..MAX_QTY. */
data class OrderItem(
    val drinkId: String,
    val qty: Int
)

/**
 * A customer soda order.
 *
 * @param totalCents grand total in cents; must equal [calculateTotal] for [items].
 * @param ts epoch millis when the order was created.
 */
data class SodaOrder(
    val orderId: String,
    val shopId: String,
    val customerName: String,
    val items: List<OrderItem>,
    val totalCents: Int,
    val ts: Long
) {
    companion object {
        const val MAX_QTY_PER_ITEM = 99
        const val MAX_ITEMS = 50

        /**
         * Builds a validated order, computing the total from [menu].
         * @throws IllegalArgumentException if validation fails.
         */
        fun create(
            shopId: String,
            customerName: String,
            items: List<OrderItem>,
            menu: List<Drink> = Drink.MENU,
            orderId: String = UUID.randomUUID().toString(),
            ts: Long = System.currentTimeMillis()
        ): SodaOrder {
            val order = SodaOrder(
                orderId = orderId,
                shopId = shopId,
                customerName = customerName,
                items = items.toList(),
                totalCents = calculateTotal(items, menu),
                ts = ts
            )
            order.validate(menu)
            return order
        }

        /** Sums (unit price * qty) per item. Unknown drink ids throw. */
        fun calculateTotal(items: List<OrderItem>, menu: List<Drink> = Drink.MENU): Int {
            var total = 0
            for (item in items) {
                val drink = menu.firstOrNull { it.id == item.drinkId }
                    ?: throw IllegalArgumentException("Unknown drinkId: ${item.drinkId}")
                total += drink.priceCents * item.qty
            }
            return total
        }
    }

    /**
     * Throws [IllegalArgumentException] on the first validation failure:
     * blank ids/names, empty or oversized item list, bad qty, unknown drink,
     * negative ts, or total mismatch.
     */
    fun validate(menu: List<Drink> = Drink.MENU) {
        require(orderId.isNotBlank()) { "orderId must not be blank" }
        require(shopId.isNotBlank()) { "shopId must not be blank" }
        require(customerName.isNotBlank()) { "customerName must not be blank" }
        require(items.isNotEmpty()) { "items must not be empty" }
        require(items.size <= MAX_ITEMS) { "too many items: ${items.size} > $MAX_ITEMS" }
        require(ts >= 0) { "ts must be >= 0" }
        for (item in items) {
            require(item.drinkId.isNotBlank()) { "drinkId must not be blank" }
            require(item.qty in 1..MAX_QTY_PER_ITEM) {
                "qty for ${item.drinkId} must be 1..$MAX_QTY_PER_ITEM, was ${item.qty}"
            }
            require(menu.any { it.id == item.drinkId }) { "Unknown drinkId: ${item.drinkId}" }
        }
        val expected = calculateTotal(items, menu)
        require(totalCents == expected) { "totalCents mismatch: $totalCents != $expected" }
    }

    /** Non-throwing validation; false if [validate] would throw. */
    fun isValid(menu: List<Drink> = Drink.MENU): Boolean =
        try {
            validate(menu)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
}
