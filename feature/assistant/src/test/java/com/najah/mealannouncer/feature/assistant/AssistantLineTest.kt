package com.najah.mealannouncer.feature.assistant

import com.najah.mealannouncer.core.model.MealUnit
import com.najah.mealannouncer.data.mistral.WebCarbEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le comportement d'une ligne de repas dicté, sans Android ni réseau.
 */
class AssistantLineTest {

    @Test
    fun `une quantité vide veut dire portion à préciser`() {
        val line = line(quantityText = "")

        assertTrue(line.needsPortion())
        assertEquals(0.0, line.carbs.grams, 0.0001)
    }

    @Test
    fun `la virgule française et les espaces sont acceptés`() {
        assertEquals(12.5, parseQuantityText("12,5"), 0.0001)
        assertEquals(1500.0, parseQuantityText("1 500"), 0.0001)
        assertEquals(0.0, parseQuantityText("beaucoup"), 0.0001)
    }

    @Test
    fun `une quantité s'affiche sans décimale inutile`() {
        assertEquals("150", formatQuantityText(150.0))
        assertEquals("12,5", formatQuantityText(12.5))
    }

    @Test
    fun `la puce d'unité va au gramme pour une valeur venue d'Internet`() {
        val web = line(unit = MealUnit.UNIT, quantityText = "1", estimate = ESTIMATE)

        assertEquals(MealUnit.GRAM, nextUnitFor(web))
    }

    @Test
    fun `la puce d'unité garde son cycle pour un produit Open Food Facts`() {
        assertEquals(MealUnit.MILLILITER, nextUnitFor(line(unit = MealUnit.GRAM)))
    }

    @Test
    fun `une valeur d'Internet en grammes se calcule`() {
        val web = line(unit = MealUnit.GRAM, quantityText = "120", estimate = ESTIMATE)

        assertEquals(24.6, web.carbs.grams, 0.01)
        assertFalse(web.needsPortion())
    }

    private fun line(
        quantityText: String = "",
        unit: MealUnit = MealUnit.GRAM,
        estimate: WebCarbEstimate? = null,
    ): AssistantLine = AssistantLine(
        id = 1L,
        name = estimate?.name ?: "aliment",
        rawText = "aliment",
        quantityText = quantityText,
        unit = unit,
        product = null,
        estimate = estimate,
    )

    private companion object {
        val ESTIMATE = WebCarbEstimate(
            name = "banane crue",
            carbsPer100g = 20.5,
            sourceUrl = "https://exemple.fr/banane",
        )
    }
}
