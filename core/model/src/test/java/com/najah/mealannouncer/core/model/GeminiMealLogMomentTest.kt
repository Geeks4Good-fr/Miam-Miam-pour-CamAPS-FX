package com.najah.mealannouncer.core.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class GeminiMealLogMomentTest {

    private val paris = ZoneId.of("Europe/Paris")

    private fun log(start: String, vararg lines: String) = GeminiMealLog(
        id = "x",
        startedAt = Instant.parse(start),
        updatedAt = Instant.parse("2026-11-23T18:07:19Z"),
        totalCarbs = 94.0,
        minCarbs = null,
        maxCarbs = null,
        meal = "potage",
        detail = "potage, pâtes",
        model = "m",
        turns = emptyList(),
        destinations = lines.toList(),
    )

    @Test
    fun `heure de CamAPS et non heure du dernier envoi`() {
        val meal = log(
            "2026-11-23T17:32:00Z",
            "18:36:00 — CamAPS : 94 g, envoi (Failed)",
            "19:05:00 — Carnet Glooko : noté après coup (94 g)",
        )
        assertEquals(Instant.parse("2026-11-23T17:36:00Z"), meal.camapsMoment(paris))
    }

    @Test
    fun `confirmé préféré`() {
        val meal = log(
            "2026-11-23T11:20:00Z",
            "12:25:00 — CamAPS : 60 g envoyés",
            "12:26:10 — CamAPS : confirmé",
        )
        assertEquals(Instant.parse("2026-11-23T11:26:10Z"), meal.camapsMoment(paris))
    }

    @Test
    fun `passage de minuit`() {
        val meal = log("2026-11-23T22:55:00Z", "00:02:00 — CamAPS : confirmé")
        assertEquals(Instant.parse("2026-11-23T23:02:00Z"), meal.camapsMoment(paris))
    }

    @Test
    fun `sans CamAPS le début de la conversation`() {
        val meal = log("2026-11-23T17:32:00Z", "19:05:00 — Carnet Glooko : noté")
        assertEquals(Instant.parse("2026-11-23T17:32:00Z"), meal.camapsMoment(paris))
    }
}
