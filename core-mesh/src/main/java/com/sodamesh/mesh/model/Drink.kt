package com.sodamesh.mesh.model

/**
 * A soft drink on the shop menu.
 *
 * @param id stable machine-readable id (e.g. "cola").
 * @param name display name.
 * @param priceCents unit price in cents.
 * @param imageKey asset/lookup key for the drink artwork.
 */
data class Drink(
    val id: String,
    val name: String,
    val priceCents: Int,
    val imageKey: String
) {
    companion object {
        /** Fixed menu of 8 soft drinks. */
        val MENU: List<Drink> = listOf(
            Drink(id = "cola", name = "Cola Classic", priceCents = 199, imageKey = "img_cola"),
            Drink(id = "cola-zero", name = "Cola Zero", priceCents = 199, imageKey = "img_cola_zero"),
            Drink(id = "lemon-lime", name = "Lemon Lime", priceCents = 179, imageKey = "img_lemon_lime"),
            Drink(id = "orange", name = "Orange Pop", priceCents = 179, imageKey = "img_orange"),
            Drink(id = "root-beer", name = "Root Beer", priceCents = 209, imageKey = "img_root_beer"),
            Drink(id = "ginger-ale", name = "Ginger Ale", priceCents = 209, imageKey = "img_ginger_ale"),
            Drink(id = "grape", name = "Grape Fizz", priceCents = 189, imageKey = "img_grape"),
            Drink(id = "sparkling-water", name = "Sparkling Water", priceCents = 149, imageKey = "img_sparkling_water")
        )

        /** Lookup helper; returns null for unknown ids. */
        fun byId(id: String): Drink? = MENU.firstOrNull { it.id == id }
    }
}
