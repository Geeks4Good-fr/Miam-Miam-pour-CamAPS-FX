package com.najah.mealannouncer.data.mistral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La fiche « par pièce » : glucides d'une pièce, ou son poids, écrit ou déduit du paquet. */
class WebCarbUnitTest {

    @Test
    fun `le poids d'une piece se deduit du paquet`() {
        val estimate = WebCarbResearcher.parse(
            "pains au chocolat Carrefour",
            """{"carbs_per_100g":45,"units_per_package":8,"package_weight_g":360,"unit_detail":"paquet de 8 × 45 g"}""",
        )
        assertEquals(45.0, estimate.unitGrams!!, 0.0001)
        assertEquals("paquet de 8 × 45 g", estimate.unitDetail)
        assertTrue(estimate.knowsUnit)
    }

    @Test
    fun `le poids ecrit passe avant le calcul par le paquet`() {
        val estimate = WebCarbResearcher.parse(
            "pain",
            """{"carbs_per_100g":45,"unit_weight_g":50,"units_per_package":8,"package_weight_g":360}""",
        )
        assertEquals(50.0, estimate.unitGrams!!, 0.0001)
    }

    @Test
    fun `les glucides par piece sont lus et bornes`() {
        val ok = WebCarbResearcher.parse("pain", """{"carbs_per_100g":45,"carbs_per_unit":"22,5"}""")
        assertEquals(22.5, ok.carbsPerUnit!!, 0.0001)
        val faux = WebCarbResearcher.parse("pain", """{"carbs_per_100g":45,"carbs_per_unit":900}""")
        assertNull("900 g de glucides pour une pièce est une erreur", faux.carbsPerUnit)
    }

    @Test
    fun `une image du meme site que la fiche est gardee`() {
        val estimate = WebCarbResearcher.parse(
            "pains au chocolat Carrefour",
            """{"carbs_per_100g":45,"source":"https://www.carrefour.fr/p/pains-au-chocolat",
               "image_url":"https://static1.carrefour.fr/medias/pain.jpg","barcode":"3560071012345"}""",
        )
        assertEquals("https://static1.carrefour.fr/medias/pain.jpg", estimate.imageUrl)
        assertEquals("3560071012345", estimate.barcode)
    }

    @Test
    fun `une image d'un autre site ou sans source est refusee`() {
        val ailleurs = WebCarbResearcher.parse(
            "pain",
            """{"carbs_per_100g":45,"source":"https://www.carrefour.fr/p","image_url":"https://images.google.com/pain.jpg"}""",
        )
        assertNull("Une image venue d'ailleurs peut montrer un autre produit", ailleurs.imageUrl)
        val sansSource = WebCarbResearcher.parse("pain", """{"carbs_per_100g":45,"image_url":"https://carrefour.fr/pain.jpg"}""")
        assertNull(sansSource.imageUrl)
        val enClair = WebCarbResearcher.parse(
            "pain",
            """{"carbs_per_100g":45,"source":"https://carrefour.fr/p","image_url":"http://carrefour.fr/pain.jpg"}""",
        )
        assertNull(enClair.imageUrl)
    }

    @Test
    fun `le nom affiche n'est pas celui du paquet`() {
        assertEquals(
            "pains au chocolat Carrefour",
            WebCarbResearcher.simpleName("8 pains au chocolat Carrefour (paquet de 360 g)"),
        )
        assertEquals("yaourts nature Danone", WebCarbResearcher.simpleName("x4 yaourts nature Danone - 500 g"))
        assertEquals("Coca-Cola zéro", WebCarbResearcher.simpleName("Coca-Cola zéro 33 cl"))
        assertEquals("riz basmati (cuit)", WebCarbResearcher.simpleName("riz basmati (cuit)"))
        assertEquals("7 Up", WebCarbResearcher.simpleName("7 Up"))
    }

    @Test
    fun `un code-barres invalide est ignore`() {
        val estimate = WebCarbResearcher.parse("pain", """{"carbs_per_100g":45,"barcode":"123"}""")
        assertNull(estimate.barcode)
    }

    @Test
    fun `une estimation moyenne n'a ni photo ni code-barres`() {
        val estimate = GeneralCarbEstimate.parse(
            "pain",
            """{"carbs_per_100g":45,"source":"https://carrefour.fr/p","image_url":"https://carrefour.fr/pain.jpg","barcode":"3560071012345"}""",
        )
        assertNull(estimate.imageUrl)
        assertNull(estimate.barcode)
    }

    @Test
    fun `sans information par piece rien n'est invente`() {
        val estimate = WebCarbResearcher.parse("riz", """{"carbs_per_100g":28,"unit_weight_g":null}""")
        assertNull(estimate.unitGrams)
        assertNull(estimate.carbsPerUnit)
        assertNull(estimate.unitDetail)
        assertFalse(estimate.knowsUnit)
    }
}
