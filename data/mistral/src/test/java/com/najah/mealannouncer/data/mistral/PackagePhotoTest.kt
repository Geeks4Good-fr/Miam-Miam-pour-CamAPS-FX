package com.najah.mealannouncer.data.mistral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ce que l'application accepte de croire d'une photo d'emballage. */
class PackagePhotoTest {

    @Test
    fun `garde les nombres recopiés de l'emballage`() {
        val reading = PackagePhotoInterpretation.parse(
            """{"description":"Je vois le dos d'un paquet de mini pâtés.","aliment_visible":"mini pâtés en croûte",
               "produit":"Mini pâtés en croûte","texte_lu":"Glucides 22,5 g — 6 pièces — Poids net 270 g — 1 pâté (45 g)",
               "poids_piece_g":45,"pieces_par_paquet":6,"poids_net_g":270,"glucides_piece_g":null,"glucides_100g":22.5,
               "lu_sur_emballage":true,"mange":"2 pièces"}""",
        )
        assertEquals("Je vois le dos d'un paquet de mini pâtés.", reading.description)
        assertEquals("mini pâtés en croûte", reading.visibleFood)
        assertEquals(45.0, reading.pieceGrams!!, 0.001)
        assertEquals(22.5, reading.carbsPer100g!!, 0.001)
        assertEquals(270.0, reading.netGrams!!, 0.001)
        assertEquals("2 pièces", reading.eaten)
        assertTrue(reading.readOnPackage)
        assertTrue(reading.knowsUnit)
    }

    /**
     * Essai du 10/11/2026 : une barre de pâté à moitié mangée, sans tableau visible, a été lue
     * « 6,5 g de glucides pour 100 g, poids net 122 g ». Sans texte recopié, rien n'est gardé.
     */
    @Test
    fun `un nombre absent du texte recopié est une invention et il est jeté`() {
        val reading = PackagePhotoInterpretation.parse(
            """{"description":"Je vois un pâté en croûte.","aliment_visible":"pâté en croûte","texte_lu":null,
               "glucides_100g":6.5,"poids_net_g":122,"pieces_par_paquet":null,"lu_sur_emballage":true,"mange":"la moitié"}""",
        )
        assertNull(reading.carbsPer100g)
        assertNull(reading.netGrams)
        assertFalse(reading.readOnPackage)
        assertFalse(reading.knowsUnit)
        assertEquals("la moitié", reading.eaten)
    }

    @Test
    fun `un nombre recopié ailleurs dans le texte ne suffit pas s'il diffère`() {
        val reading = PackagePhotoInterpretation.parse(
            """{"texte_lu":"Glucides 16 g dont sucres 6,5 g","glucides_100g":6.5,"lu_sur_emballage":true}""",
        )
        // 6,5 est bien imprimé (ligne des sucres) : le garde-fou du texte ne voit pas la ligne ;
        // la consigne interdit « dont sucres », la relecture contrôle le reste.
        assertEquals(6.5, reading.carbsPer100g!!, 0.001)
        assertTrue(PackagePhotoInterpretation.isPrinted(16.0, "Glucides 16 g dont sucres 6,5 g"))
        assertFalse(PackagePhotoInterpretation.isPrinted(1.0, "Glucides 16 g"))
        assertFalse(PackagePhotoInterpretation.isPrinted(6.0, "dont sucres 6,5 g"))
        assertTrue(PackagePhotoInterpretation.isPrinted(18.0, "Glucides 18,0 g"))
    }

    @Test
    fun `un poids estimé à l'œil n'a pas besoin d'être imprimé`() {
        val reading = PackagePhotoInterpretation.parse(
            """{"texte_lu":null,"poids_piece_g":60,"lu_sur_emballage":false}""",
        )
        assertEquals(60.0, reading.pieceGrams!!, 0.001)
        assertFalse(reading.readOnPackage)
    }

    @Test
    fun `déduit la pièce du paquet quand seul le paquet est imprimé`() {
        val reading = PackagePhotoInterpretation.parse(
            """{"texte_lu":"8 pains — 300 g","pieces_par_paquet":"8","poids_net_g":"300","lu_sur_emballage":true}""",
        )
        assertEquals(37.5, reading.pieceGrams!!, 0.001)
    }

    @Test
    fun `lit le code-barres EAN-13 imprimé sur l'emballage`() {
        val reading = PackagePhotoInterpretation.parse(
            """{"description":"Sachet de 8 pains au chocolat Carrefour Classic.","aliment_visible":"pains au chocolat",
               "produit":"Carrefour Classic 8 pains au chocolat","code_barres":"3 560070 711994",
               "texte_lu":"Carrefour Classic 8 pains au chocolat 360 g — 3 560070 711994",
               "pieces_par_paquet":8,"poids_net_g":360,"lu_sur_emballage":true}""",
        )
        assertEquals("3560070711994", reading.barcode)
        assertEquals(45.0, reading.pieceGrams!!, 0.001)
    }

    @Test
    fun `retrouve le code-barres dans texte_lu si code_barres est omis`() {
        val reading = PackagePhotoInterpretation.parse(
            """{"produit":"Carrefour Classic 8 pains au chocolat","code_barres":null,
               "texte_lu":"8 pains au chocolat 3560070711994"}""",
        )
        assertEquals("3560070711994", reading.barcode)
    }
}
