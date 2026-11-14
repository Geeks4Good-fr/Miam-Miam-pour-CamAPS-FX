package com.najah.mealannouncer.feature.assistant

import com.najah.mealannouncer.core.model.MealUnit
import com.najah.mealannouncer.data.mistral.PackageReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoLineMatcherTest {

    private val pate = AssistantLine(1, "Le Classique - Pâté en croûte (Monterrat)", "la moitié d'un pâté en croûte Simpl", "61", MealUnit.GRAM)
    private val chips = AssistantLine(2, "Chips Natures Classiques (Brets)", "un petit paquet de chips", "30", MealUnit.GRAM)

    private fun seen(food: String?, product: String? = null) = PackageReading(
        description = null, visibleFood = food, product = product, printedText = null, unitGrams = null,
        carbsPerUnit = null, piecesPerPack = null, netGrams = null, carbsPer100g = null, eaten = null,
        readOnPackage = false,
    )

    @Test
    fun `la barre de pâté va à la ligne du pâté, pas aux chips`() {
        assertEquals(pate, PhotoLineMatcher.match(seen("pâté en croûte", "Pâté en croûte Simpl"), listOf(pate, chips)))
        assertEquals(chips, PhotoLineMatcher.match(seen("chips nature"), listOf(pate, chips)))
    }

    @Test
    fun `un aliment absent du repas n'est rattaché à rien`() {
        assertNull(PhotoLineMatcher.match(seen("yaourt"), listOf(pate, chips)))
        assertNull(PhotoLineMatcher.match(seen(null), listOf(pate, chips)))
    }

    @Test
    fun `le bouton d'une ligne refuse une photo d'autre chose`() {
        assertEquals(pate, PhotoLineMatcher.match(seen("pâté en croûte"), listOf(pate)))
        assertNull(PhotoLineMatcher.match(seen("chips nature"), listOf(pate)))
        assertEquals(pate, PhotoLineMatcher.match(seen(null), listOf(pate)))
    }
}
