package com.najah.mealannouncer.data.mistral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MealReviewTest {

    @Test
    fun `lit la relecture du the a trois sucres (reponse reelle de Magistral, 29-10-2026)`() {
        val result = MealReview.parse(
            """{"raisonnement":"Le thé nature ne contient pas de glucides.","lignes":[
               {"id":1,"glucides_estimes_g":0,"plausible":false,"probleme":"Fiche incorrecte : thé sucré au lieu de thé nature.",
                "correction":{"nom":"thé infusé nature","grammes":null,"glucides_g":0}},
               {"id":2,"glucides_estimes_g":16.5,"plausible":false,"probleme":"Poids d'un morceau surestimé.",
                "correction":{"nom":null,"grammes":16.5,"glucides_g":null}}],"total_estime_g":16.5,"question":null}""",
        )
        assertEquals("Le thé nature ne contient pas de glucides.", result.reasoning)
        val the = result.line(1)!!
        assertFalse(the.plausible)
        assertEquals(0.0, the.estimatedCarbs!!, 0.0)
        assertEquals("thé infusé nature", the.correction!!.name)
        assertEquals(0.0, the.correction!!.carbs!!, 0.0)
        assertEquals(16.5, result.line(2)!!.correction!!.grams!!, 0.0)
        assertEquals(16.5, result.totalEstimate!!, 0.0)
        assertNull(result.question)
    }

    @Test
    fun `valeurs absurdes ignorees, texte null jamais lu comme « null »`() {
        val result = MealReview.parse(
            """{"raisonnement":null,"lignes":[{"id":1,"glucides_estimes_g":9000,"plausible":true,"probleme":null,
               "correction":{"nom":null,"grammes":-5,"glucides_g":"12,5"}},{"glucides_estimes_g":3}],"question":"null"}""",
        )
        val line = result.lines.single()
        assertNull("Hors bornes", line.estimatedCarbs)
        assertNull(line.problem)
        assertNull(line.correction!!.grams)
        assertEquals("La virgule française est lue", 12.5, line.correction!!.carbs!!, 0.0)
        assertEquals("", result.reasoning)
        assertNull(result.question)
    }

    @Test
    fun `une correction vide n'en est pas une`() {
        val result = MealReview.parse(
            """{"lignes":[{"id":1,"glucides_estimes_g":10,"plausible":true,"correction":{"nom":null,"grammes":null,"glucides_g":null}}]}""",
        )
        assertNull(result.line(1)!!.correction)
    }

    @Test
    fun `sans lignes — reponse invalide`() {
        val error = assertThrows(MistralException::class.java) { MealReview.parse("""{"raisonnement":"…"}""") }
        assertEquals(MistralErrorKind.INVALID_RESPONSE, error.kind)
    }

    @Test
    fun `la consigne contient les reperes et exige le raisonnement d'abord`() {
        val prompt = MealReview.SYSTEM_PROMPT
        assertTrue(prompt.contains("un morceau de sucre ≈ 5 à 6 g"))
        assertTrue(prompt.contains("thé, café, infusion nature ≈ 0 g"))
        assertTrue(prompt.contains("Réfléchis d'abord"))
        assertFalse("trimIndent appliqué", prompt.startsWith(" "))
    }
}
