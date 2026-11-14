package com.najah.mealannouncer.data.mistral

import com.najah.mealannouncer.core.model.MealUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MealExtractionTest {

    private fun failure(block: () -> Unit): MistralException =
        assertThrows(MistralException::class.java) { block() }

    @Test
    fun `lit un aliment avec sa quantite et son unite`() {
        val json = """
            {"foods":[{"name":"riz basmati","quantity":150,"unit":"gram","raw_text":"150 g de riz basmati"}]}
        """.trimIndent()
        val result = MealExtraction.parse(json)
        val food = result.foods.single()
        assertEquals("riz basmati", food.name)
        assertEquals(150.0, food.quantity!!, 0.0)
        assertEquals(MealUnit.GRAM, food.unit)
        assertEquals("150 g de riz basmati", food.rawText)
        assertTrue(!food.needsPortion())
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun `une quantite absente n'est pas inventee`() {
        val json = """{"foods":[{"name":"yaourt nature","quantity":null,"unit":"unit","raw_text":"un yaourt"}]}"""
        val food = MealExtraction.parse(json).foods.single()
        assertNull(food.quantity)
        assertTrue(food.needsPortion())
    }

    @Test
    fun `une quantite aberrante est ramenee a une portion inconnue`() {
        val json = """{"foods":[{"name":"pâtes","quantity":15000,"unit":"gram","raw_text":"pâtes"}]}"""
        assertTrue(MealExtraction.parse(json).foods.single().needsPortion())
    }

    @Test
    fun `une quantite negative ou nulle est refusee`() {
        val json = """{"foods":[{"name":"pain","quantity":0,"unit":"slice","raw_text":"pain"}]}"""
        assertTrue(MealExtraction.parse(json).foods.single().needsPortion())
    }

    @Test
    fun `accepte une quantite ecrite en texte avec une virgule`() {
        val json = """{"foods":[{"name":"compote","quantity":"12,5","unit":"gram","raw_text":"compote"}]}"""
        assertEquals(12.5, MealExtraction.parse(json).foods.single().quantity!!, 0.0)
    }

    @Test
    fun `ecarte et signale un aliment sans nom`() {
        val json = """
            {"foods":[
              {"name":"","quantity":100,"unit":"gram","raw_text":"un truc"},
              {"name":"pomme","quantity":1,"unit":"unit","raw_text":"une pomme"}
            ]}
        """.trimIndent()
        val result = MealExtraction.parse(json)
        assertEquals(1, result.foods.size)
        assertEquals("pomme", result.foods.single().name)
        assertEquals(listOf("un truc"), result.rejected)
    }

    @Test
    fun `lit toutes les unites annoncees`() {
        val json = """
            {"foods":[
              {"name":"lait","quantity":200,"unit":"milliliter","raw_text":"200 ml"},
              {"name":"pain","quantity":1,"unit":"slice","raw_text":"une tranche"},
              {"name":"café","quantity":1,"unit":"cup","raw_text":"une tasse"},
              {"name":"biscuits","quantity":2,"unit":"unit","raw_text":"deux biscuits"},
              {"name":"fromage","quantity":1,"unit":"portion","raw_text":"une portion"},
              {"name":"sauce","quantity":1,"unit":"autre","raw_text":"de la sauce"}
            ]}
        """.trimIndent()
        val units = MealExtraction.parse(json).foods.map { it.unit }
        assertEquals(
            listOf(
                MealUnit.MILLILITER,
                MealUnit.SLICE,
                MealUnit.CUP,
                MealUnit.UNIT,
                MealUnit.PORTION,
                MealUnit.UNKNOWN,
            ),
            units,
        )
    }

    @Test
    fun `accepte une liste vide`() {
        assertTrue(MealExtraction.parse("""{"foods":[]}""").foods.isEmpty())
    }

    @Test
    fun `refuse une reponse sans liste d'aliments`() {
        val error = failure { MealExtraction.parse("""{"repas":"inconnu"}""") }
        assertEquals(MistralErrorKind.INVALID_RESPONSE, error.kind)
    }

    @Test
    fun `la consigne interdit d'inventer des glucides et des portions`() {
        val prompt = MealExtraction.SYSTEM_PROMPT
        assertTrue(prompt.contains("AUCUNE valeur de glucides"))
        assertTrue(prompt.contains("quantity à null"))
        assertTrue(prompt.contains("JAMAIS"))
    }

    @Test
    fun `la question reprend la phrase dictee`() {
        assertTrue(MealExtraction.userPrompt("deux biscuits").contains("deux biscuits"))
    }
}
