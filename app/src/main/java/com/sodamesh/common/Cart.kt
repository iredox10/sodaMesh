package com.sodamesh.common

import com.sodamesh.mesh.model.Drink
import com.sodamesh.mesh.model.OrderItem

/**
 * Pure cart logic for the customer flavor.
 *
 * The cart is a [Map] of `drinkId -> qty`. Every function is a total, side-effect-free
 * transformation, so this object is unit-testable with plain JUnit (no Android, no Hilt).
 *
 * Conventions:
 * - Unknown or blank drink ids are ignored (no-ops), never throw. The canonical
 *   validation gate is [com.sodamesh.mesh.model.SodaOrder.create], which the
 *   ViewModel uses when building the order for the mesh.
 * - Quantities are clamped to `0..MAX_QTY_PER_ITEM` (matches `SodaOrder.MAX_QTY_PER_ITEM`).
 * - A qty of `<= 0` removes the line.
 */
object Cart {

    const val MAX_QTY_PER_ITEM = 99

    /** Adds [qty] of [drinkId] (default 1). Unknown/blank ids are ignored. */
    fun add(cart: Map<String, Int>, drinkId: String, qty: Int = 1): Map<String, Int> {
        if (drinkId.isBlank() || qty <= 0) return cart
        val current = cart[drinkId] ?: 0
        val next = (current + qty).coerceIn(0, MAX_QTY_PER_ITEM)
        if (next <= 0) return cart
        return cart + (drinkId to next)
    }

    /** Decrements [drinkId] by one; removes the line when it reaches zero. */
    fun removeOne(cart: Map<String, Int>, drinkId: String): Map<String, Int> {
        if (drinkId.isBlank()) return cart
        val current = cart[drinkId] ?: return cart
        return if (current <= 1) cart - drinkId else cart + (drinkId to current - 1)
    }

    /** Sets an absolute [qty] for [drinkId]; `<= 0` removes the line. Clamped to max. */
    fun setQty(cart: Map<String, Int>, drinkId: String, qty: Int): Map<String, Int> {
        if (drinkId.isBlank()) return cart
        if (qty <= 0) return cart - drinkId
        return cart + (drinkId to qty.coerceAtMost(MAX_QTY_PER_ITEM))
    }

    /** Empties the cart. */
    fun clear(): Map<String, Int> = emptyMap()

    /** Total item count (sum of quantities). */
    fun count(cart: Map<String, Int>): Int = cart.values.sum()

    /**
     * Grand total in cents for [cart] priced against [menu].
     * Lines with ids absent from [menu] are skipped (validated later by `SodaOrder.create`).
     */
    fun totalCents(cart: Map<String, Int>, menu: List<Drink> = Drink.MENU): Int {
        val prices = menu.associate { it.id to it.priceCents }
        var total = 0
        for ((drinkId, qty) in cart) {
            val unit = prices[drinkId] ?: continue
            total += unit * qty.coerceIn(0, MAX_QTY_PER_ITEM)
        }
        return total
    }

    /** Converts [cart] to mesh line items, sorted by drinkId for deterministic payloads. */
    fun toOrderItems(cart: Map<String, Int>): List<OrderItem> =
        cart
            .filter { (drinkId, qty) -> drinkId.isNotBlank() && qty > 0 }
            .map { (drinkId, qty) -> OrderItem(drinkId, qty.coerceAtMost(MAX_QTY_PER_ITEM)) }
            .sortedBy { it.drinkId }
}

/** Formats [cents] as `$d.cc` (e.g. 199 -> `$1.99`). */
fun formatCents(cents: Int): String {
    val sign = if (cents < 0) "-" else ""
    val abs = kotlin.math.abs(cents)
    return "$sign$${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}
