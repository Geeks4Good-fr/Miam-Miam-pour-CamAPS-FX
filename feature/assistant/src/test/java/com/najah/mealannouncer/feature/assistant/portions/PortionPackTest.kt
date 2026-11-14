package com.najah.mealannouncer.feature.assistant.portions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortionPackTest {

    private fun pack(vararg foods: String) =
        """{"format":"miam-miam-portions","version":1,"auteur":"test","aliments":[${foods.joinToString(",")}]}"""

    private val riz = """{"id":"riz_blanc_cuit","nom":"Riz blanc cuit","synonymes":["riz","riz blanc"],
        "pour_100g":{"glucides":28,"sucres":0.1,"fibres":0.4,"lipides":0.3,"proteines":2.7,"energie_kcal":130},
        "index_glycemique":73,"cuisson":{"etat":"cuit","rapport_cuit_sur_cru":2.6,"pour_100g_cru":{"glucides":80}},
        "frequence_fr":3,"charge_inconnue":"gardée",
        "portions":[{"grammes":150,"photo":"photos/riz_150.jpg"},{"grammes":75,"libelle":"petite","photo":"photos/riz_75.jpg"}]}"""
    private val pomme = """{"id":"pomme","nom":"Pomme","synonymes":["pomme"],"pour_100g":{"glucides":12},"portions":[{"grammes":150}]}"""
    private val painDeMie = """{"id":"pain_de_mie","nom":"Pain de mie","synonymes":["tranche de pain","pain de mie"],"pour_100g":{"glucides":45},"portions":[{"grammes":30}]}"""
    private val pdt = """{"id":"pdt","nom":"Pommes de terre","synonymes":["pommes de terre"],"pour_100g":{"glucides":17},"portions":[{"grammes":100}]}"""

    @Test
    fun `lit un paquet, trie les portions et garde les champs inconnus`() {
        val reading = PortionPack.parse(pack(riz))
        val food = reading.foods.single()
        assertEquals(listOf(75.0, 150.0), food.portions.map { it.grams })
        assertEquals(28.0, food.per100g.carbs, 0.0)
        assertEquals(2.6, food.cooking!!.cookedOverRaw!!, 0.0)
        assertTrue(food.cooking!!.changesWithCooking)
        assertEquals("gardée", food.raw.getString("charge_inconnue"))
        assertEquals(3, food.frenchFrequency)
        assertEquals(30.7, food.glycemicLoad(150.0)!!, 0.1)
    }

    @Test
    fun `refuse avec sa raison un aliment incoherent ou une photo absente`() {
        val faux = """{"id":"x","nom":"Faux","pour_100g":{"glucides":120},"portions":[{"grammes":10}]}"""
        val sucre = """{"id":"y","nom":"Sucré","pour_100g":{"glucides":10,"sucres":30},"portions":[{"grammes":10}]}"""
        val reading = PortionPack.parse(pack(faux, sucre, riz)) { it != "photos/riz_75.jpg" }
        assertTrue(reading.foods.isEmpty())
        assertTrue(reading.rejected[0].contains("hors de 0 à 100"))
        assertTrue(reading.rejected[1].contains("plus de sucres"))
        assertTrue(reading.rejected[2].contains("photo manquante"))
    }

    @Test
    fun `un fichier qui n'est pas un paquet est refuse`() {
        assertTrue(PortionPack.parse("""{"format":"autre"}""").rejected.single().contains("ce n'est pas"))
        assertTrue(PortionPack.parse("pas du json").rejected.single().contains("illisible"))
    }

    @Test
    fun `Atwater signale une energie qui ne colle pas`() {
        assertNull(PortionPack.atwaterDoubt(Nutrients(carbs = 28.0, fat = 0.3, protein = 2.7, kcal = 130.0)))
        assertNotNull(PortionPack.atwaterDoubt(Nutrients(carbs = 28.0, fat = 0.3, protein = 2.7, kcal = 1300.0)))
    }

    private val bank = PortionPack.parse(pack(riz, pomme, painDeMie, pdt)).foods.map { it to false }

    @Test
    fun `reconnait l'aliment par son nom ou un synonyme`() {
        assertEquals("riz_blanc_cuit", PortionBank.matchIn(bank, "riz basmati")!!.food.id)
        assertEquals("pomme", PortionBank.matchIn(bank, "une pomme")!!.food.id)
        assertEquals("pomme", PortionBank.matchIn(bank, "pomme golden")!!.food.id)
        assertEquals("pdt", PortionBank.matchIn(bank, "pommes de terre vapeur")!!.food.id)
        assertEquals("pain_de_mie", PortionBank.matchIn(bank, "tranche de pain complet")!!.food.id)
    }

    @Test
    fun `ne confond pas un autre aliment qui contient le mot`() {
        assertNull("jus de pomme n'est pas une pomme", PortionBank.matchIn(bank, "jus de pomme"))
        assertNull("pain au chocolat n'est pas du pain de mie", PortionBank.matchIn(bank, "pain au chocolat"))
        assertNull(PortionBank.matchIn(bank, "compote de pomme"))
    }

    @Test
    fun `riz cru ou a cuire prend les valeurs avant cuisson`() {
        val cuit = PortionBank.matchIn(bank, "riz blanc")!!
        assertFalse(cuit.raw)
        assertEquals(28.0, cuit.per100g.carbs, 0.0)
        val cru = PortionBank.matchIn(bank, "riz à cuire")!!
        assertTrue(cru.raw)
        assertEquals(80.0, cru.per100g.carbs, 0.0)
        assertEquals("bank:riz_blanc_cuit:cru", cru.toProduct().id)
        assertTrue(cru.toProduct().name.contains("cru"))
    }

    @Test
    fun `un import de l'utilisateur passe devant la base a synonyme egal`() {
        val mien = PortionPack.parse(pack("""{"id":"mon_riz","nom":"Riz","synonymes":["riz blanc"],"pour_100g":{"glucides":30},"portions":[{"grammes":100}]}""")).foods
        val mixed = bank + mien.map { it to true }
        assertEquals("mon_riz", PortionBank.matchIn(mixed, "riz blanc")!!.food.id)
    }
}
