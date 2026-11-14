package com.najah.mealannouncer.feature.assistant

import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.core.model.MealUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'état de l'écran : ce que l'utilisateur peut faire, et à quel moment.
 *
 * Ces règles ne sont pas décoratives : elles décident si un total partiel peut être écrit
 * dans CamAPS FX pendant qu'une question attend encore sa réponse.
 */
class AssistantUiStateTest {

    private val prince = FoodProduct(
        id = "1",
        barcode = null,
        name = "Prince",
        brand = null,
        carbsPer100g = 70.0,
    )

    private fun line(id: Long, quantityText: String = "", product: FoodProduct? = prince) =
        AssistantLine(
            id = id,
            name = "Prince",
            rawText = "prince",
            quantityText = quantityText,
            unit = MealUnit.GRAM,
            product = product,
        )

    private val ready = AssistantUiState(
        phase = AssistantPhase.READY,
        lines = listOf(line(1, "25")),
    )

    @Test
    fun `un repas chiffre et pret peut etre ecrit dans CamAPS`() {
        assertTrue(ready.canWrite)
        assertEquals(18, ready.camapsGrams)
    }

    @Test
    fun `un dialogue ouvert ferme l'ecriture dans CamAPS`() {
        val enDialogue = ready.copy(
            dialogue = PortionDialogue(
                lineId = 2,
                lineName = "Riz",
                question = "Quelle quantité de riz ?",
            ),
        )
        assertFalse(enDialogue.canWrite)
    }

    @Test
    fun `une dictee en cours n'ecrit rien`() {
        assertFalse(ready.copy(phase = AssistantPhase.RECORDING).canWrite)
    }

    @Test
    fun `un aliment sans glucides ne s'ecrit pas a zero`() {
        val vide = AssistantUiState(
            phase = AssistantPhase.READY,
            lines = listOf(line(1, "25", product = null)),
        )
        assertEquals(0.0, vide.total.grams, 0.0001)
        assertFalse(vide.canWrite)
    }

    @Test
    fun `les portions manquantes se comptent`() {
        val state = ready.copy(lines = listOf(line(1, ""), line(2, "25")))
        assertEquals(1, state.missingPortions)
        assertEquals(1, state.resolvedCount)
        assertEquals(1, state.unresolvedCount)
    }

    @Test
    fun `un aliment sans produit n'est jamais compte comme zero dans le total`() {
        val state = ready.copy(
            lines = listOf(line(1, "25"), line(2, "100", product = null)),
        )
        assertEquals(17.5, state.total.grams, 0.0001)
        assertEquals(1, state.unresolvedCount)
    }

    @Test
    fun `une proposition de photo ferme l'ecriture dans CamAPS`() {
        // Règle de sûreté : tant que la carte « D'après la photo » est à l'écran, le repas n'est
        // pas complet et rien ne doit partir dans CamAPS.
        val avecPhoto = ready.copy(photo = PhotoProposal(lines = emptyList()))
        assertFalse(avecPhoto.canWrite)
        // Et la photo en cours de lecture non plus.
        assertFalse(ready.copy(photo = PhotoProposal(busy = true)).canWrite)
    }

    @Test
    fun `le dialogue ouvert est signale a l'ecran`() {
        assertFalse(ready.dialogueOpen)
        assertTrue(
            ready.copy(
                dialogue = PortionDialogue(1, "Prince", "Combien de biscuits ?"),
            ).dialogueOpen,
        )
    }
}
