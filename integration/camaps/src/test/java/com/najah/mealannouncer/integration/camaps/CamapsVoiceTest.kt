package com.najah.mealannouncer.integration.camaps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La compréhension du dialogue vocal : quel type l'utilisateur a dit, et s'il a vraiment confirmé.
 *
 * Les deux fonctions gardent chacune une sûreté : un type douteux reste `null` (on redemande),
 * et une confirmation n'est jamais lue sur un « non » ou un doute (on n'appuie pas sur
 * « Continuer »).
 */
class CamapsVoiceTest {

    @Test
    fun `hypoglycemie reconnue meme mal dite`() {
        assertEquals(CamapsMealType.HYPO, CamapsVoice.matchMealType("traitement de l'hypo"))
        assertEquals(CamapsMealType.HYPO, CamapsVoice.matchMealType("hypoglycémie"))
    }

    @Test
    fun `absorption lente reconnue avant le mot repas`() {
        assertEquals(CamapsMealType.SLOW, CamapsVoice.matchMealType("repas à absorption lente"))
        assertEquals(CamapsMealType.SLOW, CamapsVoice.matchMealType("le lent"))
    }

    @Test
    fun `repas ou collation`() {
        assertEquals(CamapsMealType.MEAL, CamapsVoice.matchMealType("repas ou collation"))
        assertEquals(CamapsMealType.MEAL, CamapsVoice.matchMealType("une collation"))
    }

    @Test
    fun `reponse hors sujet - aucun type`() {
        assertNull(CamapsVoice.matchMealType("euh je sais pas"))
        assertNull(CamapsVoice.matchMealType(""))
    }

    @Test
    fun `comprehension permissive - synonymes courants`() {
        assertEquals(CamapsMealType.SLOW, CamapsVoice.matchMealType("glucides lents"))
        assertEquals(CamapsMealType.HYPO, CamapsVoice.matchMealType("une correction d'hypoglycémie"))
        assertEquals(CamapsMealType.HYPO, CamapsVoice.matchMealType("je me resucre"))
        assertEquals(CamapsMealType.MEAL, CamapsVoice.matchMealType("un snack"))
        assertEquals(CamapsMealType.MEAL, CamapsVoice.matchMealType("un goûter"))
    }

    @Test
    fun `l'enonce unique contient le repas, le total et la question`() {
        val phrase = CamapsVoice.announceAndAsk("lait chocolaté, pitch fraise", 42)
        assertTrue(phrase.contains("lait chocolaté"))
        assertTrue(phrase.contains("42 grammes"))
        assertTrue(phrase.contains("Quel type"))
        // Sans résumé, on annonce seulement le total.
        assertFalse(CamapsVoice.announceAndAsk("", 42).contains("J'ai compris"))
    }

    @Test
    fun `oui franc vaut confirmation`() {
        assertTrue(CamapsVoice.isConfirmation("oui"))
        assertTrue(CamapsVoice.isConfirmation("Ok"))
        assertTrue(CamapsVoice.isConfirmation("je confirme"))
        assertTrue(CamapsVoice.isConfirmation("d'accord"))
        assertTrue(CamapsVoice.isConfirmation("c'est bon"))
    }

    @Test
    fun `negation ou doute ne confirme pas`() {
        assertFalse(CamapsVoice.isConfirmation("non"))
        assertFalse(CamapsVoice.isConfirmation("non c'est bon"))
        assertFalse(CamapsVoice.isConfirmation("attends"))
        assertFalse(CamapsVoice.isConfirmation("pas encore"))
        assertFalse(CamapsVoice.isConfirmation(""))
    }
}
