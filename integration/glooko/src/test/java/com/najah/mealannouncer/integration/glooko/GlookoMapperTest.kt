package com.najah.mealannouncer.integration.glooko

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Conversion CSV → documents Nightscout, sur les particularités de l'export réel.
 *
 * Chaque test reproduit un piège constaté dans le fichier du 24/10/2026 : doublons,
 * contradictions, valeurs aberrantes, alertes répliquées.
 */
class GlookoMapperTest {

    private val mapper = GlookoToNightscoutMapper()
    private val export = GlookoSample.export()

    private fun plan(
        windowHours: Long = 48,
        includeSgv: Boolean = true,
    ): GlookoPlan = mapper.plan(
        export,
        GlookoPlanOptions(
            nowMs = GlookoSample.NOW_MS,
            windowHours = windowHours,
            includeSgv = includeSgv,
        ),
    )

    private fun GlookoPlan.of(kind: GlookoDocumentKind) = documents.filter { it.kind == kind }

    private fun GlookoDocument.json() = JSONObject(payload)

    // ---- Glycémies ---------------------------------------------------------------

    @Test
    fun `trois lignes identiques ne font quune glycémie`() {
        val plan = plan()
        assertEquals(2, plan.counts[GlookoDocumentKind.SGV])
        // Deux glycémies répliquées, plus l'alerte de sécurité doublée à l'identique.
        assertEquals(3, plan.skipped[GlookoSkip.DUPLICATE_IN_FILE] ?: 0)
        assertEquals(1, plan.skipped[GlookoSkip.OUTSIDE_WINDOW] ?: 0)
    }

    @Test
    fun `la glycémie est arrondie et datée en UTC`() {
        val sgv = plan().of(GlookoDocumentKind.SGV).first().json()
        assertEquals(225, sgv.getInt("sgv"))
        assertEquals(Instant.parse("2026-10-17T10:00:00Z").toEpochMilli(), sgv.getLong("date"))
        assertEquals("2026-10-17T10:00:00.000Z", sgv.getString("dateString"))
        assertEquals("Glooko-CamAPS", sgv.getString("device"))
        assertNotNull(sgv.getString("identifier"))
    }

    @Test
    fun `les glycémies peuvent être refusées`() {
        val plan = plan(includeSgv = false)
        assertEquals(0, plan.entryCount)
        assertTrue((plan.skipped[GlookoSkip.DISABLED] ?: 0) > 0)
    }

    // ---- Repas -------------------------------------------------------------------

    @Test
    fun `un bolus normal et son calcul ne font quun repas`() {
        val meals = plan().of(GlookoDocumentKind.MEAL_BOLUS)
        assertEquals(1, meals.size)
        val json = meals.single().json()
        assertEquals("Meal Bolus", json.getString("eventType"))
        assertEquals(5.0, json.getDouble("insulin"), 0.001)
        assertEquals(60.0, json.getDouble("carbs"), 0.001)
        assertTrue(json.optString("notes").isEmpty())
    }

    @Test
    fun `une contradiction entre pompe et calcul est signalée sans être corrigée`() {
        val correction = plan().of(GlookoDocumentKind.CORRECTION_BOLUS)
            .single { it.json().optDouble("insulin") == 9.89 }
        val json = correction.json()
        // La dose réellement délivrée reste celle de la pompe : on ne recalcule rien.
        assertEquals(9.89, json.getDouble("insulin"), 0.001)
        assertEquals("CamAPS a calculé 30.0 U", json.getString("notes"))
    }

    @Test
    fun `des glucides sans bolus restent un apport`() {
        val carbs = plan().of(GlookoDocumentKind.CARB_CORRECTION)
        assertEquals(1, carbs.size)
        assertEquals(20.0, carbs.single().json().getDouble("carbs"), 0.001)
        assertEquals("Carb Correction", carbs.single().json().getString("eventType"))
    }

    @Test
    fun `un micro-bolus étendu nest pas un bolus combiné`() {
        val micro = plan().of(GlookoDocumentKind.CORRECTION_BOLUS)
            .single { it.json().optDouble("insulin") == 0.03 }
        val json = micro.json()
        assertTrue(json.getString("notes").startsWith("bolus étendu 0.03 U"))
        assertTrue(!json.has("duration"))
        assertEquals(0, plan().counts[GlookoDocumentKind.COMBO_BOLUS] ?: 0)
    }

    // ---- Débits ------------------------------------------------------------------

    @Test
    fun `un débit par minute et une durée jusquau changement suivant`() {
        val basal = plan().of(GlookoDocumentKind.TEMP_BASAL)
        assertEquals(3, basal.size)

        val durations = basal.map { it.json().getLong("duration") }
        // 10:00 → 10:01 → 10:03 : une puis deux minutes ; le dernier est plafonné.
        assertEquals(listOf(1L, 2L, 30L), durations)

        val rates = basal.map { it.json().getDouble("rate") }
        // 7.36 l'emporte sur la valeur identique de la même minute, sans être moyennée.
        assertEquals(listOf(2.0, 7.36, 1.5), rates)
        assertTrue(basal.all { it.json().getBoolean("absolute") })
    }

    @Test
    fun `le débit en cours au début de la fenêtre est conservé`() {
        val short = plan(windowHours = 1)
        // Une seule ligne de basal reste dans l'heure écoulée, mais elle donne le débit en
        // cours : la courbe ne commence pas par un trou.
        assertEquals(1, short.counts[GlookoDocumentKind.TEMP_BASAL])
    }

    // ---- Évènements --------------------------------------------------------------

    @Test
    fun `deux modes de durées différentes à la même minute coexistent`() {
        val modes = plan().of(GlookoDocumentKind.PUMP_MODE)
        assertEquals(3, modes.size)
        val sameMinute = modes.filter { it.timestampMs == GlookoCsv.paris("2026-10-17T12:10") }
        assertEquals(2, sameMinute.size)
        assertEquals(2, sameMinute.map { it.identifier }.distinct().size)
    }

    @Test
    fun `une alerte strictement répliquée nest écrite quune fois`() {
        val alerts = plan().of(GlookoDocumentKind.SAFETY_ALERT)
        assertEquals(1, alerts.size)
        assertEquals("Announcement", alerts.single().json().getString("eventType"))
        assertEquals("Alerte de sécurité - Capteur (1 min)", alerts.single().json().getString("notes"))
    }

    @Test
    fun `les autres évènements gardent leur nature`() {
        val plan = plan()
        assertEquals(1, plan.counts[GlookoDocumentKind.RESERVOIR])
        assertEquals(1, plan.counts[GlookoDocumentKind.OVERRIDE])
        assertEquals(1, plan.counts[GlookoDocumentKind.TOTAL_DAILY_DOSE])
        assertEquals("Insulin Change", plan.of(GlookoDocumentKind.RESERVOIR).single().json().getString("eventType"))
        assertEquals("CamAPS Boost (2 h)", plan.of(GlookoDocumentKind.OVERRIDE).single().json().getString("notes"))
    }

    @Test
    fun `un libellé inconnu est compté et non inventé`() {
        // « cgm settings » n'a pas d'équivalent Nightscout : il est écarté, pas traduit.
        assertEquals(1, plan().skipped[GlookoSkip.UNMAPPED_EVENT])
        assertEquals(2, plan().skipped[GlookoSkip.PROFILE_ONLY])
        assertEquals(0, plan().counts[GlookoDocumentKind.COMBO_BOLUS] ?: 0)
    }

    // ---- Profil ------------------------------------------------------------------

    @Test
    fun `le profil reprend les réglages sans en inventer`() {
        val profile = plan().profile
        assertNotNull(profile)
        val json = JSONObject(profile!!.payload)
        assertEquals("Glooko-CamAPS", json.getString("defaultProfile"))

        val store = json.getJSONObject("store").getJSONObject("Glooko-CamAPS")
        assertEquals(2.0, store.getJSONArray("basal").getJSONObject(0).getDouble("value"), 0.001)
        assertEquals(1.5, store.getJSONArray("carbratio").getJSONObject(0).getDouble("value"), 0.001)
        assertEquals(15.0, store.getJSONArray("sens").getJSONObject(0).getDouble("value"), 0.001)
        assertEquals(70.26, store.getJSONArray("target_low").getJSONObject(0).getDouble("value"), 0.001)
        assertEquals(180.15, store.getJSONArray("target_high").getJSONObject(0).getDouble("value"), 0.001)
        assertEquals(5.0, store.getDouble("dia"), 0.001)
        assertEquals("mg/dl", store.getString("units"))
        assertEquals("Europe/Paris", store.getString("timezone"))
    }

    @Test
    fun `deux copies des réglages ne changent pas l empreinte`() {
        // Les douze copies de l'export réel ne diffèrent que par leur date : une seule
        // écriture de profil doit en sortir.
        val first = mapper.plan(export, GlookoPlanOptions(nowMs = GlookoSample.NOW_MS))
        val withOtherTime = GlookoParser.parse(
            GlookoCsv()
                .row(time = "17/10/2026 12:00", bg = "120")
                .row(
                    time = "17/10/2026 12:30",
                    event = "pump settings",
                    settings = GlookoCsv.quoted(GlookoCsv.settingsJson(time = "2026-10-24T04:21:00.000Z")),
                )
                .build(),
        )
        val second = mapper.plan(withOtherTime, GlookoPlanOptions(nowMs = GlookoSample.NOW_MS))
        assertEquals(first.profile!!.contentHash, second.profile!!.contentHash)
        assertEquals(first.profile.identifier, second.profile.identifier)
    }
}
