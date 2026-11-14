package com.najah.mealannouncer.integration.glookoexport

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Les règles du déclenchement par repas.
 *
 * Chacune protège quelque chose de concret : un import coupé qui ne doit pas se réveiller, un
 * repas qui doit exporter, ou deux exports pour un seul repas. Ces décisions ne se voient pas
 * à l'écran — un export oublié ressemble à un export réussi —, d'où ces tests.
 */
class GlookoMealTriggerTest {

    private val debounceMs = 30 * 60_000L
    private val now = 1_700_000_000_000L

    private fun decide(
        enabled: Boolean = true,
        exportOnMeal: Boolean = true,
        lastAttemptMs: Long = 0L,
        nowMs: Long = now,
    ) = decideMealTrigger(
        enabled = enabled,
        exportOnMeal = exportOnMeal,
        lastAttemptMs = lastAttemptMs,
        nowMs = nowMs,
        debounceMs = debounceMs,
    )

    @Test
    fun `import desarme - aucun export, meme au premier repas`() {
        assertEquals(GlookoMealTrigger.DISABLED, decide(enabled = false))
    }

    @Test
    fun `reglage a chaque repas coupe - aucun export`() {
        assertEquals(GlookoMealTrigger.OFF, decide(exportOnMeal = false))
    }

    @Test
    fun `import desarme prime sur le reglage`() {
        assertEquals(
            GlookoMealTrigger.DISABLED,
            decide(enabled = false, exportOnMeal = false),
        )
    }

    @Test
    fun `premier repas - export programme`() {
        assertEquals(GlookoMealTrigger.SCHEDULED, decide())
    }

    @Test
    fun `repas juste apres un passage - rien de neuf a chercher`() {
        assertEquals(
            GlookoMealTrigger.DEBOUNCED,
            decide(lastAttemptMs = now - 5 * 60_000L),
        )
    }

    @Test
    fun `repas a la limite exacte du delai - export programme`() {
        assertEquals(
            GlookoMealTrigger.SCHEDULED,
            decide(lastAttemptMs = now - debounceMs),
        )
    }

    @Test
    fun `une milliseconde avant la limite - encore trop tot`() {
        assertEquals(
            GlookoMealTrigger.DEBOUNCED,
            decide(lastAttemptMs = now - debounceMs + 1),
        )
    }

    @Test
    fun `horodatage dans le futur - horloge remise a l'heure, on exporte`() {
        assertEquals(
            GlookoMealTrigger.SCHEDULED,
            decide(lastAttemptMs = now + 3_600_000L),
        )
    }
}
