package com.najah.mealannouncer.data.mistral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebCarbLookupTest {

    private fun failure(block: () -> Unit): MistralException =
        assertThrows(MistralException::class.java) { block() }

    private class FakeSearch(private val answer: String) : MistralWebSearch {
        var question: String? = null
        var instructions: String? = null

        override fun ask(apiKey: String, question: String, instructions: String): String {
            this.question = question
            this.instructions = instructions
            return answer
        }
    }

    @Test
    fun `lit une valeur et sa source`() {
        val estimate = WebCarbResearcher.parse(
            "riz basmati",
            """{"name":"riz basmati cuit","carbs_per_100g":28.2,"source":"https://exemple.fr/riz"}""",
        )
        assertEquals("riz basmati cuit", estimate.name)
        assertEquals(28.2, estimate.carbsPer100g, 0.0)
        assertEquals("https://exemple.fr/riz", estimate.sourceUrl)
    }

    @Test
    fun `une source qui n'est pas une adresse est ignoree`() {
        val estimate = WebCarbResearcher.parse(
            "riz",
            """{"name":"riz","carbs_per_100g":28,"source":"un site"}""",
        )
        assertNull(estimate.sourceUrl)
    }

    @Test
    fun `accepte un nombre ecrit en texte`() {
        val estimate = WebCarbResearcher.parse(
            "riz",
            """{"carbs_per_100g":"28,2"}""",
        )
        assertEquals(28.2, estimate.carbsPer100g, 0.0)
    }

    @Test
    fun `le nom de repli est celui de l'utilisateur`() {
        val estimate = WebCarbResearcher.parse("compote de pommes", """{"carbs_per_100g":12}""")
        assertEquals("compote de pommes", estimate.name)
    }

    @Test
    fun `refuse une valeur au-dela du possible`() {
        val error = failure { WebCarbResearcher.parse("sucre", """{"carbs_per_100g":140}""") }
        assertEquals(MistralErrorKind.INVALID_RESPONSE, error.kind)
    }

    @Test
    fun `refuse une valeur nulle ou absente`() {
        assertEquals(
            MistralErrorKind.INVALID_RESPONSE,
            failure { WebCarbResearcher.parse("eau", """{"carbs_per_100g":0}""") }.kind,
        )
        assertEquals(
            MistralErrorKind.INVALID_RESPONSE,
            failure { WebCarbResearcher.parse("eau", """{"name":"eau"}""") }.kind,
        )
    }

    @Test
    fun `une erreur du modele devient un echec explicite`() {
        val error = failure {
            WebCarbResearcher.parse("plat maison", """{"error":"aucune valeur fiable trouvée"}""")
        }
        assertEquals(MistralErrorKind.INVALID_RESPONSE, error.kind)
        assertTrue(error.message!!.contains("plat maison"))
        assertTrue(error.message!!.contains("aucune valeur fiable"))
    }

    @Test
    fun `la recherche pose la question pour 100 grammes`() {
        val search = FakeSearch("""{"carbs_per_100g":28}""")
        WebCarbResearcher(search).lookup("clef", "riz basmati")
        assertTrue(search.question!!.contains("riz basmati"))
        assertTrue(search.question!!.contains("100 grammes"))
        assertTrue(search.instructions!!.contains("glucides TOTAUX"))
    }
}
