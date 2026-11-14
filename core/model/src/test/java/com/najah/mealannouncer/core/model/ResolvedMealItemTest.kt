package com.najah.mealannouncer.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Libellés d'une ligne de repas : ceux qui restent dans l'historique doivent être
 * compréhensibles des mois plus tard, sans le total à côté.
 */
class ResolvedMealItemTest {

    private fun line(
        name: String = "Biscuit Prince chocolat",
        brand: String? = "LU",
        quantity: Double = 2.0,
        unit: MealUnit = MealUnit.UNIT,
        carbs: Double = 22.0,
    ) = ResolvedMealItem(
        productName = name,
        brand = brand,
        quantity = quantity,
        unit = unit,
        carbsPerUnit = 11.0,
        totalCarbs = Carbs.of(carbs),
        source = FoodSource.USER_FAVORITE,
        confidence = 0.9f,
        basis = CarbBasis.PER_UNIT,
    )

    @Test
    fun `le nom complet ajoute la marque entre parentheses`() {
        assertEquals("Biscuit Prince chocolat (LU)", line().displayName())
    }

    @Test
    fun `le nom complet reste sobre sans marque`() {
        assertEquals("Biscuit Prince chocolat", line(brand = null).displayName())
        assertEquals("Biscuit Prince chocolat", line(brand = "  ").displayName())
    }

    @Test
    fun `un decompte s ecrit avec un fois`() {
        assertEquals("Biscuit Prince chocolat ×2", line().shortLabel())
    }

    @Test
    fun `un volume garde son unite`() {
        val candy = line(
            name = "Candy Up chocolat",
            brand = "Candy Up",
            quantity = 200.0,
            unit = MealUnit.MILLILITER,
            carbs = 18.0,
        )
        assertEquals("Candy Up chocolat 200 ml", candy.shortLabel())
    }

    @Test
    fun `un poids garde ses grammes`() {
        val bread = line(name = "Pain complet", brand = null, quantity = 30.0, unit = MealUnit.GRAM, carbs = 15.0)
        assertEquals("Pain complet 30 g", bread.shortLabel())
    }

    @Test
    fun `une tasse s ecrit avec une quantite entiere`() {
        val cup = line(name = "Candy Up", brand = null, quantity = 1.0, unit = MealUnit.CUP, carbs = 18.0)
        assertEquals("Candy Up ×1 tasse", cup.shortLabel())
    }

    @Test
    fun `le resume garde le total`() {
        assertEquals("Biscuit Prince chocolat ×2 — 22 g", line().summary())
    }

    @Test
    fun `une quantite decimale garde sa virgule`() {
        assertEquals("Biscuit Prince chocolat ×1,5", line(quantity = 1.5).shortLabel())
    }
}
