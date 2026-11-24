package com.najah.mealannouncer.data.nightscout

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TreatmentPayloadTest {

    @Test
    fun `decrit un traitement de type Carbs`() {
        val json = TreatmentPayload.build(sampleMeal)
        assertEquals("Carbs", json.getString("eventType"))
        assertEquals(40, json.getInt("carbs"))
    }

    @Test
    fun `une note de repas Gemini ne porte aucun glucide compte`() {
        val json = TreatmentPayload.note("Miam-Miam (Gemini) : 65 g annoncés à CamAPS — pain 25 g", Instant.parse("2026-11-23T11:30:00Z"))
        assertEquals("Note", json.getString("eventType"))
        assertEquals(false, json.has("carbs"))
        assertEquals("Miam-Miam (Gemini) : 65 g annoncés à CamAPS — pain 25 g", json.getString("notes"))
        assertEquals("2026-11-23T11:30:00.000Z", json.getString("created_at"))
    }

    @Test
    fun `signe l envoi sans exposer de donnee personnelle`() {
        val json = TreatmentPayload.build(sampleMeal)
        assertEquals("Meal Announcer", json.getString("enteredBy"))
    }

    @Test
    fun `reprend la note du repas`() {
        val json = TreatmentPayload.build(sampleMeal)
        assertEquals("Prince ×2 + Candy Up 200 ml", json.getString("notes"))
    }

    @Test
    fun `date le traitement en UTC avec les millisecondes`() {
        val json = TreatmentPayload.build(sampleMeal)
        assertEquals("2026-10-24T12:34:56.000Z", json.getString("created_at"))
    }

    @Test
    fun `arrondit les glucides comme CamAPS`() {
        val json = TreatmentPayload.build(
            carbs = 0,
            notes = "",
            createdAt = Instant.parse("2026-10-24T12:34:56Z"),
        )
        assertEquals(0, json.getInt("carbs"))
    }

    @Test
    fun `produit un JSON relisible par Nightscout`() {
        val text = TreatmentPayload.toJson(sampleMeal)
        val reparsed = JSONObject(text)
        assertEquals(40, reparsed.getInt("carbs"))
        assertEquals("Carbs", reparsed.getString("eventType"))
    }
}
