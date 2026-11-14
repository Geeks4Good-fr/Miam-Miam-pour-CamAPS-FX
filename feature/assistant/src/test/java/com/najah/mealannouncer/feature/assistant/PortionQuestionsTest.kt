package com.najah.mealannouncer.feature.assistant

import com.najah.mealannouncer.core.model.Carbs
import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.core.model.MealUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Les phrases du dialogue de portion : elles sont **dites**, donc elles doivent se lire à
 * voix haute sans ambiguïté ni parenthèses.
 */
class PortionQuestionsTest {

    private val biscuits = FoodProduct(
        id = "1",
        barcode = null,
        name = "Prince Goût Vanille",
        brand = "LU",
        carbsPer100g = 70.0,
        defaultPortionGrams = 25.0,
        servingLabel = "2 biscuits (25 g)",
        packageGrams = 300.0,
    )

    private val verre = FoodProduct(
        id = "2",
        barcode = null,
        name = "Jus d'orange",
        brand = null,
        carbsPer100g = 9.0,
        defaultPortionGrams = 200.0,
        servingLabel = "1 verre (200 ml)",
    )

    private fun line(
        name: String = "Prince",
        product: FoodProduct? = biscuits,
        quantityText: String = "",
    ): AssistantLine =
        AssistantLine(
            id = 1L,
            name = name,
            rawText = name,
            quantityText = quantityText,
            unit = MealUnit.GRAM,
            product = product,
        )

    @Test
    fun `la question d'ouverture donne les reperes de la fiche`() {
        val phrase = PortionQuestions.forLine(line())
        assertEquals(
            "Quelle quantité de Prince avez-vous mangée ? Une portion, c'est 2 biscuits de " +
                "12,5 g, le paquet fait 300 g. Dites un poids en grammes, ou un nombre de " +
                "biscuits, ou une fraction.",
            phrase,
        )
    }

    @Test
    fun `sans fiche la question reste courte et ne promet rien`() {
        val phrase = PortionQuestions.forLine(line(name = "Tajine", product = null))
        assertEquals(
            "Quelle quantité de Tajine avez-vous mangée ? Dites un poids en grammes, " +
                "ou une fraction.",
            phrase,
        )
        assertFalse(phrase.contains("portion"))
    }

    @Test
    fun `une portion d'un seul article se dit au singulier`() {
        val phrase = PortionQuestions.forLine(line(name = "Jus", product = verre))
        assertTrue(phrase.contains("Une portion, c'est un verre de 200 g."))
    }

    @Test
    fun `la confirmation redit le poids et les glucides`() {
        val phrase = PortionQuestions.noted(line(quantityText = "25"), Carbs.of(17.5))
        assertEquals("C'est noté : 25 g, soit 17,5 g de glucides.", phrase)
    }

    @Test
    fun `la confirmation sans glucides ne dit pas zero`() {
        val phrase = PortionQuestions.noted(line(quantityText = "25"), null)
        assertEquals("C'est noté : 25 g.", phrase)
    }

    @Test
    fun `la fin du dialogue donne le total`() {
        assertEquals(
            "C'est tout. Total : 50 g de glucides.",
            PortionQuestions.finished(Carbs.of(50.0), unresolved = 0),
        )
    }

    @Test
    fun `la fin du dialogue dit combien d'aliments restent sans portion`() {
        val phrase = PortionQuestions.finished(Carbs.of(30.0), unresolved = 2)
        assertTrue(phrase.contains("Total : 30 g de glucides."))
        assertTrue(phrase.contains("2 aliment(s) restent sans portion"))
    }

    @Test
    fun `la fin du dialogue refuse de calculer un total incomplet`() {
        val phrase = PortionQuestions.finished(Carbs.ZERO, unresolved = 1)
        assertTrue(phrase.contains("je ne peux pas calculer le total"))
    }

    @Test
    fun `les phrases de secours restent courtes`() {
        assertTrue(PortionQuestions.gramsPlease().contains("grammes"))
        assertTrue(PortionQuestions.notUnderstood().contains("150 grammes"))
    }
}
