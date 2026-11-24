package com.najah.mealannouncer.feature.assistant

import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.data.mistral.DirectProduct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Essai réel du 24/11/2026 : pour « biscuits Prince classiques » (LU), l'appli envoyait à Gemini
 * des frites, des carottes râpées et un taboulé Carrefour, rapprochés par le seul mot « classiques ».
 */
class DirectProductFinderRankTest {

    private fun product(id: String, name: String, brand: String?, carbs: Double? = 50.0) =
        FoodProduct(id = id, barcode = id, name = name, brand = brand, carbsPer100g = carbs)

    private val prince = DirectProduct(name = "biscuits Prince classiques", brand = "LU", barcode = null)

    @Test
    fun `un seul mot banal ne suffit pas`() {
        val pool = listOf(
            product("1", "Special four classiques", "Carrefour"),
            product("2", "Carottes Râpées à l'Huile d'Olive, Les Classiques", "bon app' Carrefour"),
            product("3", "Taboulé aux 5 Légumes, Les Classiques", "Carrefour Marchandises Internationales"),
        )

        assertTrue(DirectProductFinder.rank(prince, pool).isEmpty())
    }

    @Test
    fun `la fiche de la marque dite passe devant`() {
        val pool = listOf(
            product("1", "Biscuits fourrés goût chocolat Prince", "Carrefour"),
            product("2", "Prince goût chocolat", "LU"),
            product("3", "Special four classiques", "Carrefour"),
        )

        assertEquals(listOf("2", "1"), DirectProductFinder.rank(prince, pool).map { it.id })
    }

    @Test
    fun `une fiche sans glucides est ecartee`() {
        val pool = listOf(product("1", "Prince goût chocolat", "LU", carbs = null))

        assertTrue(DirectProductFinder.rank(prince, pool).isEmpty())
    }

    @Test
    fun `un produit d un seul mot se contente de ce mot`() {
        val nutella = DirectProduct(name = "Nutella", brand = null, barcode = null)
        val pool = listOf(product("1", "Nutella", "Ferrero"), product("2", "Pâte à tartiner", "Carrefour"))

        assertEquals(listOf("1"), DirectProductFinder.rank(nutella, pool).map { it.id })
    }
}
