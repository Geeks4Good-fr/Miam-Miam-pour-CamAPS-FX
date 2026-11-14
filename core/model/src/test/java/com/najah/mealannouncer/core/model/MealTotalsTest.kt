package com.najah.mealannouncer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Le total d'un repas ne doit jamais compter un aliment non chiffré comme zéro. */
class MealTotalsTest {

    private val prince = FoodProduct(
        id = "prince",
        barcode = null,
        name = "Biscuit Prince",
        brand = "LU",
        carbsPerUnit = 11.0,
        source = FoodSource.USER_FAVORITE,
    )

    private val candyUp = FoodProduct(
        id = "candy-up",
        barcode = null,
        name = "Candy Up chocolat",
        brand = "Candy Up",
        carbsPer100ml = 9.0,
        source = FoodSource.OPEN_FOOD_FACTS_ONLINE,
    )

    @Test
    fun `total du cas de reference`() {
        val intent = MealIntent(
            rawTranscript = "Je vais manger deux biscuits Prince et une tasse de Candy Up au chocolat.",
            items = listOf(
                MealItem(
                    rawText = "deux biscuits Prince",
                    normalizedProductName = "biscuit prince",
                    brand = "Prince",
                    quantity = 2.0,
                    unit = MealUnit.UNIT,
                    matchedProduct = prince,
                    confidence = 0.95f,
                ),
                MealItem(
                    rawText = "une tasse de Candy Up au chocolat",
                    normalizedProductName = "candy up chocolat",
                    quantity = 1.0,
                    unit = MealUnit.CUP,
                    matchedProduct = candyUp,
                    confidence = 0.9f,
                ),
            ),
            capturedAt = Instant.parse("2026-10-24T02:10:00Z"),
        )

        val total = MealTotals.compute(intent)

        assertEquals(40.0, total.total.grams, 0.0001)
        assertEquals(2, total.resolved.size)
        assertFalse(total.hasIncomplete)
        assertEquals(40, total.camapsGrams())
    }

    @Test
    fun `un aliment non resolu est signale et exclu du total`() {
        val intent = MealIntent(
            rawTranscript = "un yaourt et une pomme",
            items = listOf(
                MealItem(
                    rawText = "un yaourt",
                    normalizedProductName = "yaourt",
                    quantity = 1.0,
                    unit = MealUnit.UNIT,
                    matchedProduct = prince, // produit trouvé par erreur par le résolveur
                ),
                MealItem(
                    rawText = "une pomme",
                    normalizedProductName = "pomme",
                    quantity = 1.0,
                    unit = MealUnit.UNIT,
                    matchedProduct = null,
                ),
            ),
        )

        val total = MealTotals.compute(intent)

        assertEquals(11.0, total.total.grams, 0.0001)
        assertEquals(1, total.resolved.size)
        assertEquals(1, total.incomplete.size)
        assertEquals(CarbUnavailableReason.MISSING_PRODUCT, total.incomplete.first().reason)
    }

    @Test
    fun `une valeur saisie a la main est reprise telle quelle`() {
        val intent = MealIntent(
            rawTranscript = "deux biscuits",
            items = listOf(
                MealItem(
                    rawText = "deux biscuits",
                    normalizedProductName = "biscuit",
                    quantity = 2.0,
                    unit = MealUnit.UNIT,
                    matchedProduct = null,
                    carbs = Carbs(24.0),
                ),
            ),
        )

        val total = MealTotals.compute(intent)

        assertEquals(24.0, total.total.grams, 0.0001)
        assertFalse(total.hasIncomplete)
        assertEquals(FoodSource.MANUAL, total.resolved.first().source)
    }

    @Test
    fun `un repas vide donne zero sans erreur`() {
        val total = MealTotals.compute(MealIntent.empty(""))
        assertEquals(0.0, total.total.grams, 0.0)
        assertTrue(total.isEmpty)
        assertFalse(total.hasIncomplete)
    }

    @Test
    fun `somme de lignes corrigees`() {
        val lignes = listOf(
            ResolvedMealItem(
                productName = "Prince",
                brand = "LU",
                quantity = 2.0,
                unit = MealUnit.UNIT,
                carbsPerUnit = 11.0,
                totalCarbs = Carbs(22.0),
                source = FoodSource.USER_FAVORITE,
                confidence = 1f,
                basis = CarbBasis.PER_UNIT,
            ),
            ResolvedMealItem(
                productName = "Candy Up",
                brand = null,
                quantity = 1.0,
                unit = MealUnit.CUP,
                carbsPerUnit = 18.0,
                totalCarbs = Carbs(18.0),
                source = FoodSource.OPEN_FOOD_FACTS_CACHE,
                confidence = 0.8f,
                basis = CarbBasis.PER_100_ML,
            ),
        )
        assertEquals(40.0, MealTotals.totalOf(lignes).grams, 0.0001)
    }
}
