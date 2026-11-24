package com.najah.mealannouncer.core.database

import com.najah.mealannouncer.core.database.repository.GeminiMealLogJson
import com.najah.mealannouncer.core.model.GeminiMealLog
import com.najah.mealannouncer.core.model.GeminiTurn
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class GeminiMealLogJsonTest {

    private val answer = """
        ### Récapitulatif
        | Élément | Glucides |
        | :--- | :--- |
        | **Pain** | 20 à 25 g |
        Total : **~65 g** (« moyenne »)
    """.trimIndent()

    private val log = GeminiMealLog(
        id = "repas-1",
        startedAt = Instant.parse("2026-11-23T11:30:00Z"),
        updatedAt = Instant.parse("2026-11-23T11:32:00Z"),
        totalCarbs = 65.0,
        minCarbs = 57.0,
        maxCarbs = 73.0,
        meal = "carottes, daube, pommes de terre, pain",
        detail = "pain 45 g : 25 g, pommes de terre 175 g : 30 g",
        model = "google/gemini-4-hydrogen",
        turns = listOf(
            GeminiTurn(fromUser = true, text = "Calcule mes glucides", withPhoto = true),
            GeminiTurn(fromUser = false, text = answer, totalCarbs = 65.0),
            GeminiTurn(fromUser = true, text = "La totalité"),
            GeminiTurn(fromUser = false, text = "Environ 65 g.", totalCarbs = null),
        ),
        destinations = listOf("12:31:00 — CamAPS : 65 g saisis", "12:32:00 — Nightscout : note envoyée"),
    )

    @Test
    fun `la conversation entiere revient intacte, tableaux et guillemets compris`() {
        val back = GeminiMealLogJson.toDomain(GeminiMealLogJson.toEntity(log))
        assertEquals(log, back)
        assertEquals(2, back.exchanges)
        assertEquals(1, back.photos)
        assertEquals(answer, back.turns[1].text)
    }
}
