package com.najah.mealannouncer.feature.assistant

import com.najah.mealannouncer.core.model.MealUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class MealDescriptionTest {

    private fun line(id: Long, name: String, quantity: String, unit: MealUnit = MealUnit.GRAM) =
        AssistantLine(id = id, name = name, rawText = name, quantityText = quantity, unit = unit)

    @Test
    fun `aliments avec portions`() {
        val description = MealDescription.of(
            listOf(
                line(1, "biscuits Prince tout choco", "40"),
                line(2, "riz", "150"),
            ),
        )
        assertEquals("biscuits Prince tout choco (40 g), riz (150 g)", description)
    }

    @Test
    fun `un aliment sans portion est nomme sans quantite`() {
        val description = MealDescription.of(
            listOf(
                line(1, "riz", "150"),
                line(2, "banane", ""),
            ),
        )
        assertEquals("riz (150 g), banane", description)
    }

    @Test
    fun `une quantite nulle n'est pas affichee comme zero`() {
        assertEquals("pomme", MealDescription.of(listOf(line(1, "pomme", "0"))))
    }

    @Test
    fun `repas vide donne une description vide`() {
        assertEquals("", MealDescription.of(emptyList()))
    }

    @Test
    fun `l'unite est respectee`() {
        assertEquals(
            "lait (200 ml)",
            MealDescription.of(listOf(line(1, "lait", "200", MealUnit.MILLILITER))),
        )
    }
}
