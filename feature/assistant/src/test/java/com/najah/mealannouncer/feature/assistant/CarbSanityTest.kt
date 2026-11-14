package com.najah.mealannouncer.feature.assistant

import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.core.model.FoodSource
import com.najah.mealannouncer.core.model.MealUnit
import com.najah.mealannouncer.data.mistral.ConversationFood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Les garde-fous codés en dur, nés du thé à trois sucres compté 123 g (29/10/2026). */
class CarbSanityTest {

    private fun food(name: String, quantity: Double?, unit: MealUnit, grams: Double? = null, raw: String = name) =
        ConversationFood(id = 1, name = name, quantity = quantity, unit = unit, grams = grams, assumption = null, rawText = raw)

    private fun product(name: String, per100g: Double? = null, per100ml: Double? = null) = FoodProduct(
        id = name, barcode = null, name = name, brand = null,
        carbsPer100g = per100g, carbsPer100ml = per100ml, source = FoodSource.OPEN_FOOD_FACTS_ONLINE,
    )

    @Test
    fun `un the infuse ne prend jamais la valeur d'un the en poudre`() {
        val the = food("thé", 1.0, MealUnit.CUP)
        assertTrue(CarbSanity.incompatible(the, product("Thé citron instantané", per100g = 37.0)))
        assertTrue(CarbSanity.incompatible(the, product("Thé vert en feuilles", per100g = 2.0)))
        assertTrue("Une fiche de thé sucrée pour 100 g, sans valeur pour 100 ml", CarbSanity.incompatible(the, product("Thé pêche", per100g = 8.0)))
        assertFalse(CarbSanity.incompatible(the, product("Thé infusé", per100ml = 0.2)))
    }

    @Test
    fun `une boisson en volume refuse un sirop ou une poudre, sauf si la personne l'a dit`() {
        assertTrue(CarbSanity.incompatible(food("chocolat chaud", 250.0, MealUnit.MILLILITER), product("Chocolat en poudre", per100g = 80.0)))
        assertFalse(
            "La personne parle elle-même de poudre",
            CarbSanity.incompatible(food("chocolat en poudre", 20.0, MealUnit.GRAM), product("Chocolat en poudre", per100g = 80.0)),
        )
        assertFalse(CarbSanity.incompatible(food("jus d'orange", 200.0, MealUnit.MILLILITER), product("Pur jus d'orange", per100ml = 9.0)))
    }

    @Test
    fun `un aliment solide n'est pas concerne`() {
        assertFalse(CarbSanity.incompatible(food("pain de mie", 2.0, MealUnit.SLICE), product("Pain de mie", per100g = 45.0)))
        assertFalse("« théorique » n'est pas « thé »", CarbSanity.incompatible(food("gâteau théorique", 100.0, MealUnit.GRAM), product("Gâteau en sachet", per100g = 50.0)))
    }

    @Test
    fun `trois sucres a 10 g piece deviennent 3 x 5,5 g`() {
        val fixed = CarbSanity.fixSugarCubes(food("sucre blanc en morceaux", 3.0, MealUnit.UNIT, grams = 30.0, raw = "trois sucres"))
        assertEquals(16.5, fixed.grams!!, 0.001)
        assertEquals("1 morceau ≈ 5,5 g", fixed.assumption)
        assertEquals("sucre blanc en morceaux", fixed.name)

        val sansPoids = CarbSanity.fixSugarCubes(food("sucre", 2.0, MealUnit.UNIT))
        assertEquals("sucre en morceaux", sansPoids.name)
        assertEquals(11.0, sansPoids.grams!!, 0.001)
    }

    @Test
    fun `un poids de morceau plausible, le sucre en poudre et les aliments sucres restent intacts`() {
        val juste = food("sucre en morceaux", 2.0, MealUnit.UNIT, grams = 11.0)
        assertSame(juste, CarbSanity.fixSugarCubes(juste))
        val poudre = food("sucre en poudre", 1.0, MealUnit.UNIT, grams = 5.0, raw = "une cuillère de sucre")
        assertSame(poudre, CarbSanity.fixSugarCubes(poudre))
        val biscuits = food("biscuits sucrés", 2.0, MealUnit.UNIT, grams = 20.0)
        assertSame("« sucrés » n'est pas du sucre", biscuits, CarbSanity.fixSugarCubes(biscuits))
    }

    @Test
    fun `un ecart compte au-dela de 8 g et d'un quart`() {
        assertTrue("thé : 92,5 g contre 0", CarbSanity.discrepant(92.5, 0.0))
        assertTrue("sucre : 30 g contre 16,5", CarbSanity.discrepant(30.0, 16.5))
        assertFalse("biscuits : 14,3 g contre 14", CarbSanity.discrepant(14.3, 14.0))
        assertFalse("7 g d'écart, c'est sous le seuil", CarbSanity.discrepant(20.0, 13.0))
        assertFalse("gros repas : 10 g sur 120 g, moins d'un quart", CarbSanity.discrepant(120.0, 110.0))
    }

    @Test
    fun `un repas trop charge ne part jamais seul`() {
        assertTrue(CarbSanity.tooLarge(listOf(121.0)))
        assertTrue(CarbSanity.tooLarge(listOf(80.0, 80.0)))
        assertFalse(CarbSanity.tooLarge(listOf(60.0, 40.0)))
    }
}
