package com.najah.mealannouncer.data.mistral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ce que l'application accepte de croire d'une photo d'assiette.
 *
 * Le modèle propose des aliments et des poids ; tout est vérifié ici : un aliment sans nom
 * exploitable est **signalé** (le faire disparaître ferait croire que la photo n'a rien montré),
 * un poids hors bornes devient `null` sans emporter l'aliment, et au-delà de huit aliments on
 * dit ce qu'on écarte au lieu de couper net.
 */
class PlateInterpretationTest {

    @Test
    fun `lit les aliments vus et leurs poids`() {
        val json = """
            {"foods":[{"name":"poulet rôti","grammes":120},{"name":"riz basmati","grammes":150}]}
        """.trimIndent()
        val reading = PlateInterpretation.parse(json)
        assertEquals(2, reading.foods.size)
        assertEquals(PlateFood(name = "poulet rôti", grams = 120.0), reading.foods[0])
        assertEquals(PlateFood(name = "riz basmati", grams = 150.0), reading.foods[1])
        assertTrue(reading.rejected.isEmpty())
    }

    @Test
    fun `un aliment sans poids est garde sans chiffre`() {
        // Une assiette ne dit pas toujours son poids : le nom reste, la question de portion
        // viendra après. Inventer un chiffre ici fausserait tout le reste.
        val reading = PlateInterpretation.parse("""{"foods":[{"name":"salade verte","grammes":null}]}""")
        assertEquals(PlateFood(name = "salade verte", grams = null), reading.foods.single())
    }

    @Test
    fun `un poids hors bornes devient null sans perdre l aliment`() {
        val reading = PlateInterpretation.parse(
            """{"foods":[{"name":"une miette","grammes":0.5},{"name":"un buffet","grammes":5000}]}""",
        )
        assertEquals(2, reading.foods.size)
        assertNull(reading.foods[0].grams)
        assertNull(reading.foods[1].grams)
        assertEquals("une miette", reading.foods[0].name)
        assertEquals("un buffet", reading.foods[1].name)
    }

    @Test
    fun `un poids ecrit en texte avec une virgule est lu`() {
        val reading = PlateInterpretation.parse("""{"foods":[{"name":"pâtes","grammes":"175,5"}]}""")
        assertEquals(175.5, reading.foods.single().grams!!, 0.0)
    }

    @Test
    fun `un poids illisible ne donne rien`() {
        val reading = PlateInterpretation.parse(
            """{"foods":[{"name":"riz","grammes":"environ 150 g"},{"name":"pain","grammes":true}]}""",
        )
        assertNull(reading.foods[0].grams)
        assertNull(reading.foods[1].grams)
    }

    @Test
    fun `un aliment sans nom est signale au lieu de disparaitre`() {
        val json = """
            {"foods":[{"name":"   ","grammes":100},{"name":"","grammes":50},{"name":"riz","grammes":150}]}
        """.trimIndent()
        val reading = PlateInterpretation.parse(json)
        assertEquals(listOf(PlateFood(name = "riz", grams = 150.0)), reading.foods)
        // Deux entrées sans nom : elles portent le rang de la ligne, donc on peut en parler.
        assertEquals(listOf("aliment 1", "aliment 2"), reading.rejected)
    }

    @Test
    fun `un nom demesure est signale`() {
        val trop = "poulet " + "x".repeat(80)
        val reading = PlateInterpretation.parse("""{"foods":[{"name":"$trop","grammes":100}]}""")
        assertTrue(reading.foods.isEmpty())
        assertEquals(1, reading.rejected.size)
    }

    @Test
    fun `les espaces multiples d un nom sont resserres`() {
        val reading = PlateInterpretation.parse("""{"foods":[{"name":" riz   basmati\t","grammes":150}]}""")
        assertEquals("riz basmati", reading.foods.single().name)
    }

    @Test
    fun `au dela de huit aliments les suivants sont signales`() {
        val items = (1..10).joinToString(",") { """{"name":"aliment $it","grammes":100}""" }
        val reading = PlateInterpretation.parse("""{"foods":[$items]}""")
        assertEquals(8, reading.foods.size)
        assertEquals(listOf("aliment 9", "aliment 10"), reading.rejected)
    }

    @Test
    fun `une assiette vide rend une liste vide`() {
        val reading = PlateInterpretation.parse("""{"foods":[]}""")
        assertTrue(reading.foods.isEmpty())
        assertTrue(reading.rejected.isEmpty())
    }

    @Test
    fun `une reponse sans liste d aliments est refusee`() {
        val error = assertThrows(MistralException::class.java) {
            PlateInterpretation.parse("""{"aliments":[{"name":"riz"}]}""")
        }
        assertEquals(MistralErrorKind.INVALID_RESPONSE, error.kind)
    }

    @Test
    fun `une reponse sans json est refusee`() {
        val error = assertThrows(MistralException::class.java) {
            PlateInterpretation.parse("Je ne vois pas d'assiette sur cette photo.")
        }
        assertEquals(MistralErrorKind.INVALID_RESPONSE, error.kind)
    }

    @Test
    fun `un json encadre de texte reste lisible`() {
        // Les modèles encadrent parfois leur JSON d'un bloc Markdown : c'est le même contenu.
        val reponse = """
            Voici ce que je vois :
            ```json
            {"foods":[{"name":"gratin de courgettes","grammes":250}]}
            ```
        """.trimIndent()
        val reading = PlateInterpretation.parse(reponse)
        assertEquals(PlateFood(name = "gratin de courgettes", grams = 250.0), reading.foods.single())
    }

    @Test
    fun `la question porte la precision de l utilisateur`() {
        val sans = PlateInterpretation.userPrompt(null)
        assertTrue(sans.contains("Photo : une assiette"))
        assertTrue(!sans.contains("L'utilisateur précise"))

        val avec = PlateInterpretation.userPrompt("  c'est un gratin  ")
        assertTrue(avec.contains("L'utilisateur précise : « c'est un gratin »"))

        // Une précision vide n'est pas une précision : rien à dire au modèle.
        assertTrue(!PlateInterpretation.userPrompt("   ").contains("L'utilisateur précise"))
    }

    @Test
    fun `un emballage ou un code-barres photographié hors repas est accepté`() {
        val reading = PlateInterpretation.parse(
            """{"description":"Je vois un sachet de 8 pains au chocolat Carrefour Classic.",
               "foods":[{"name":"8 pains au chocolat Carrefour Classic","grammes":null,"code_barres":"3560070711994"}]}""",
        )
        assertEquals(
            listOf(
                PlateFood(
                    name = "8 pains au chocolat Carrefour Classic",
                    grams = null,
                    barcode = "3560070711994",
                ),
            ),
            reading.foods,
        )
    }

    @Test
    fun `un code-barres seul sans nom lisible reçoit un intitulé par défaut`() {
        val reading = PlateInterpretation.parse(
            """{"description":"Je vois un code-barres.","foods":[{"name":"","grammes":null,"code_barres":"3560070711994"}]}""",
        )
        assertEquals(
            listOf(
                PlateFood(
                    name = "produit code-barres 3560070711994",
                    grams = null,
                    barcode = "3560070711994",
                ),
            ),
            reading.foods,
        )
    }

    @Test
    fun `la photo part en data uri jpeg`() {
        // FF D8 FF : l'entête d'un JPEG, que la base64 rend « /9j/ ».
        val uri = PlateInterpretation.dataUri(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        assertEquals("data:image/jpeg;base64,", uri.substring(0, 23))
        assertEquals("/9j/", uri.removePrefix("data:image/jpeg;base64,"))
    }
}
