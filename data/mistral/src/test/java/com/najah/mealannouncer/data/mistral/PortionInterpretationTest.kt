package com.najah.mealannouncer.data.mistral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PortionInterpretationTest {

    @Test
    fun `lit un poids avec le calcul qui y mene`() {
        val json = """
            {"grammes":25,"compris":"deux biscuits","detail":"2 biscuits x 12,5 g = 25 g","question":null}
        """.trimIndent()
        val estimate = PortionInterpretation.parse(json)
        assertEquals(25.0, estimate.grams!!, 0.0)
        assertEquals("deux biscuits", estimate.understood)
        assertEquals("2 biscuits x 12,5 g = 25 g", estimate.detail)
        assertNull(estimate.question)
        assertTrue(estimate.hasGrams)
    }

    @Test
    fun `une question remplace le poids quand la base manque`() {
        val json = """
            {"grammes":null,"compris":"un tiers","detail":null,"question":"Un tiers de quoi ? Du paquet ou de la portion ?"}
        """.trimIndent()
        val estimate = PortionInterpretation.parse(json)
        assertNull(estimate.grams)
        assertFalse(estimate.hasGrams)
        assertEquals("Un tiers de quoi ? Du paquet ou de la portion ?", estimate.question)
    }

    @Test
    fun `un poids hors bornes est refuse`() {
        assertNull(PortionInterpretation.parse("""{"grammes":0.5,"compris":"une miette"}""").grams)
        assertNull(PortionInterpretation.parse("""{"grammes":5000,"compris":"un kilo"}""").grams)
        assertNull(PortionInterpretation.parse("""{"grammes":-20,"compris":"moins"}""").grams)
    }

    @Test
    fun `un poids ecrit en texte avec une virgule est lu`() {
        assertEquals(
            25.5,
            PortionInterpretation.parse("""{"grammes":"25,5","compris":"25,5 g"}""").grams!!,
            0.0,
        )
    }

    @Test
    fun `un poids illisible ne devient pas un nombre`() {
        assertNull(PortionInterpretation.parse("""{"grammes":"beaucoup"}""").grams)
        assertNull(PortionInterpretation.parse("""{"grammes":"25 g environ"}""").grams)
        assertNull(PortionInterpretation.parse("""{"grammes":true}""").grams)
    }

    @Test
    fun `une question vide ou absente vaut null`() {
        assertNull(PortionInterpretation.parse("""{"grammes":25,"question":"  "}""").question)
        assertNull(PortionInterpretation.parse("""{"grammes":25}""").question)
        assertNull(PortionInterpretation.parse("""{"grammes":25,"question":null}""").question)
    }

    @Test
    fun `une question sur plusieurs lignes est remise sur une seule`() {
        val estimate = PortionInterpretation.parse(
            """{"grammes":null,"question":"Un tiers de quoi ?\n\n  Du paquet   ou de la portion ?"}""",
        )
        assertEquals("Un tiers de quoi ? Du paquet ou de la portion ?", estimate.question)
    }

    @Test
    fun `un texte trop long est coupe`() {
        val long = "a".repeat(300)
        val estimate = PortionInterpretation.parse("""{"grammes":25,"compris":"$long","detail":"$long"}""")
        assertEquals(120, estimate.understood.length)
        assertEquals(120, estimate.detail.length)
    }

    @Test
    fun `un JSON encadre de texte est quand meme lu`() {
        val texte = """
            Voici ma réponse :
            ```json
            {"grammes":30,"compris":"une part","detail":"1 part de 30 g"}
            ```
        """.trimIndent()
        assertEquals(30.0, PortionInterpretation.parse(texte).grams!!, 0.0)
    }

    @Test
    fun `une reponse sans objet JSON est une erreur de contrat`() {
        assertThrows(MistralException::class.java) {
            PortionInterpretation.parse("je ne peux pas répondre")
        }
    }

    @Test
    fun `l'invite rappelle la fiche et la reponse`() {
        val invite = PortionInterpretation.userPrompt(
            food = "Prince Goût Vanille",
            servingGrams = 25.0,
            servingLabel = "2 biscuits (25 g)",
            packageGrams = 300.0,
            answer = "  deux biscuits  ",
        )
        assertTrue(invite.contains("Prince Goût Vanille"))
        assertTrue(invite.contains("Portion indiquée : 25 g"))
        assertTrue(invite.contains("« 2 biscuits (25 g) »"))
        assertTrue(invite.contains("Poids du paquet : 300 g"))
        assertTrue(invite.contains("« deux biscuits »"))
    }

    @Test
    fun `l'invite dit quand la fiche ne donne rien`() {
        val invite = PortionInterpretation.userPrompt(
            food = "Tajine",
            servingGrams = null,
            servingLabel = null,
            packageGrams = null,
            answer = "un tiers",
        )
        assertTrue(invite.contains("Portion indiquée : inconnue"))
        assertTrue(invite.contains("Poids du paquet : inconnu"))
        assertFalse(invite.contains("Libellé de portion"))
    }

    @Test
    fun `l'invite système interdit les glucides et exige une question`() {
        val prompt = PortionInterpretation.SYSTEM_PROMPT
        assertTrue(prompt.contains("AUCUNE valeur de glucides"))
        assertTrue(prompt.contains("de quoi"))
        assertTrue(prompt.contains("1 à 3000 grammes"))
    }
}
