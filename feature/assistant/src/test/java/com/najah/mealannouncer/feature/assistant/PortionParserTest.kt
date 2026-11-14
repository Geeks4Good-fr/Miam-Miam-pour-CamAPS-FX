package com.najah.mealannouncer.feature.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortionParserTest {

    /** Une fiche complète : portion de deux biscuits, paquet de 300 g. */
    private val biscuits = PortionContext(
        name = "Prince Goût Vanille",
        servingGrams = 25.0,
        servingLabel = "2 biscuits (25 g)",
        packageGrams = 300.0,
    )

    /** Une fiche sans portion ni paquet : la base de calcul manque. */
    private val tajine = PortionContext(name = "Tajine de poulet")

    /** Une fiche dont la portion est elle-même comptée en portions. */
    private val compote = PortionContext(
        name = "Compote de pomme",
        servingGrams = 100.0,
        servingLabel = "1 portion (100 g)",
        packageGrams = 400.0,
    )

    private fun grams(reading: PortionReading): Double {
        assertTrue("attendu un poids, obtenu $reading", reading is PortionReading.Grams)
        return (reading as PortionReading.Grams).grams
    }

    private fun detail(reading: PortionReading): String =
        (reading as PortionReading.Grams).detail

    private fun question(reading: PortionReading): String {
        assertTrue("attendu une question, obtenu $reading", reading is PortionReading.Ask)
        return (reading as PortionReading.Ask).question
    }

    // --- Poids dit directement ---------------------------------------------------

    @Test
    fun `un poids en grammes est pris tel quel`() {
        val reading = PortionParser.read("150 g", biscuits)
        assertEquals(150.0, grams(reading), 0.0)
        assertEquals("150 g", detail(reading))
        assertEquals("150 g", (reading as PortionReading.Grams).understood)
    }

    @Test
    fun `un poids en kilos est converti`() {
        assertEquals(1_500.0, grams(PortionParser.read("1,5 kg", biscuits)), 0.0)
    }

    @Test
    fun `un poids sans unite mais seul est accepte`() {
        assertEquals(80.0, grams(PortionParser.read("80", biscuits)), 0.0)
    }

    @Test
    fun `un poids hors bornes n'est pas une portion`() {
        assertTrue(PortionParser.read("5000 g", biscuits) is PortionReading.Unclear)
        assertTrue(PortionParser.read("0 g", biscuits) is PortionReading.Unclear)
    }

    // --- Comptes d'articles ------------------------------------------------------

    @Test
    fun `deux biscuits se convertissent avec le poids d'un biscuit`() {
        val reading = PortionParser.read("deux biscuits", biscuits)
        assertEquals(25.0, grams(reading), 0.0)
        assertEquals("Deux biscuits × 12,5 g = 25 g", detail(reading))
    }

    @Test
    fun `un seul biscuit donne le poids d'un article`() {
        val reading = PortionParser.read("un biscuit", biscuits)
        assertEquals(12.5, grams(reading), 0.0)
        assertTrue(detail(reading).contains("12,5 g"))
    }

    @Test
    fun `trois biscuits donnent trois fois le poids d'un article`() {
        assertEquals(37.5, grams(PortionParser.read("trois biscuits", biscuits)), 0.0)
    }

    @Test
    fun `un article etranger a la fiche fait demander des grammes`() {
        val reading = PortionParser.read("deux tranches", biscuits)
        assertTrue(question(reading).contains("combien de grammes"))
    }

    @Test
    fun `sans portion connue deux biscuits ne se convertissent pas`() {
        val reading = PortionParser.read("deux biscuits", tajine)
        assertTrue(question(reading).contains("combien de grammes"))
    }

    @Test
    fun `une portion se compte avec la portion de la fiche`() {
        assertEquals(100.0, grams(PortionParser.read("une portion", compote)), 0.0)
        assertEquals(25.0, grams(PortionParser.read("une portion", biscuits)), 0.0)
    }

    @Test
    fun `une portion sans poids connu fait demander des grammes`() {
        val reading = PortionParser.read("une portion", tajine)
        assertTrue(question(reading).contains("combien de grammes"))
    }

    @Test
    fun `un contenant entier se compte avec le poids du paquet`() {
        val reading = PortionParser.read("deux sachets", compote)
        assertEquals(800.0, grams(reading), 0.0)
        assertEquals("Deux sachets × 400 g = 800 g", detail(reading))
    }

    @Test
    fun `une assiette ne se convertit pas et fait demander des grammes`() {
        val reading = PortionParser.read("une assiette", biscuits)
        assertTrue(question(reading).contains("combien de grammes"))
    }

    // --- Fractions et pourcentages ----------------------------------------------

    @Test
    fun `la moitie du paquet se calcule avec le paquet`() {
        val reading = PortionParser.read("la moitié du paquet", biscuits)
        assertEquals(150.0, grams(reading), 0.0)
        assertEquals("La moitié du paquet de 300 g = 150 g", detail(reading))
    }

    @Test
    fun `un tiers du paquet se calcule avec le paquet`() {
        assertEquals(100.0, grams(PortionParser.read("un tiers du paquet", biscuits)), 0.0)
    }

    @Test
    fun `trois quarts du paquet se calculent avec le paquet`() {
        val reading = PortionParser.read("trois quarts du paquet", biscuits)
        assertEquals(225.0, grams(reading), 0.0)
        assertTrue(detail(reading).startsWith("Les trois quarts"))
    }

    @Test
    fun `un quart de la portion se calcule avec la portion`() {
        assertEquals(25.0, grams(PortionParser.read("un quart de la portion", compote)), 0.0)
    }

    @Test
    fun `une fraction sans base fait demander de quoi elle est la fraction`() {
        val reading = PortionParser.read("un tiers", biscuits)
        val phrase = question(reading)
        assertTrue(phrase.contains("de quoi"))
        assertTrue(phrase.contains("du paquet") && phrase.contains("de la portion"))
    }

    @Test
    fun `une fraction de paquet sans poids de paquet fait demander le poids`() {
        val reading = PortionParser.read("un tiers du paquet", tajine)
        assertTrue(question(reading).contains("Ce paquet pèse combien de grammes"))
    }

    @Test
    fun `une fraction de contenant inconnu fait demander de quoi elle est la fraction`() {
        val yaourt = PortionContext("Yaourt nature", servingGrams = 125.0)
        val reading = PortionParser.read("la moitié du pot", yaourt)
        assertTrue(question(reading).contains("de quoi"))
    }

    @Test
    fun `un poids ecrit a cote d'une fraction sert de base`() {
        val lait = PortionContext("Lait d'amande")
        assertEquals(100.0, grams(PortionParser.read("un demi sachet de 200 g", lait)), 0.0)
    }

    @Test
    fun `un pourcentage se calcule avec la portion`() {
        val reading = PortionParser.read("50 %", biscuits)
        assertEquals(12.5, grams(reading), 0.0)
        assertEquals("50 % de la portion de 25 g = 12,5 g", detail(reading))
    }

    @Test
    fun `un pourcentage sans portion connue fait demander des grammes`() {
        val reading = PortionParser.read("50 %", tajine)
        assertTrue(question(reading).contains("combien de grammes"))
    }

    // --- Ce qui n'est pas compris -------------------------------------------------

    @Test
    fun `une phrase incompréhensible est avouee comme telle`() {
        val reading = PortionParser.read("je ne sais plus", biscuits)
        assertTrue(reading is PortionReading.Unclear)
        assertEquals("je ne sais plus", (reading as PortionReading.Unclear).understood)
    }

    @Test
    fun `une reponse vide n'est pas comprise`() {
        assertTrue(PortionParser.read("   ", biscuits) is PortionReading.Unclear)
    }

    @Test
    fun `une reponse vide ne donne aucune question`() {
        assertNull(
            (PortionParser.read("", biscuits) as? PortionReading.Ask)?.question,
        )
    }
}
