package com.najah.mealannouncer.data.nightscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlookoMatchTest {

    private val at = 1_795_000_000_000L
    private val minute = 60_000L

    private fun ref(id: String, offsetMin: Long, carbs: Double?, by: String = "Glooko-sync") =
        NightscoutTreatmentRef(
            createdAtMs = at + offsetMin * minute,
            eventType = "Carb Correction",
            enteredBy = by,
            identifier = null,
            id = id,
            carbs = carbs,
        )

    @Test
    fun `une entree Glooko-sync de meme valeur et proche est reconnue`() {
        val match = matchingGlookoEntry(listOf(ref("a", 12, 65.0)), carbs = 65, atMs = at)
        assertEquals("a", match?.id)
    }

    @Test
    fun `un gramme d'ecart passe, deux non`() {
        assertEquals("a", matchingGlookoEntry(listOf(ref("a", 5, 64.0)), 65, at)?.id)
        assertNull(matchingGlookoEntry(listOf(ref("a", 5, 63.0)), 65, at))
    }

    @Test
    fun `hors de la fenetre de 30 minutes, rien`() {
        assertNull(matchingGlookoEntry(listOf(ref("a", 31, 65.0)), 65, at))
        assertEquals("b", matchingGlookoEntry(listOf(ref("b", -30, 65.0)), 65, at)?.id)
    }

    @Test
    fun `seules les entrees Glooko-sync comptent, et la plus proche gagne`() {
        val found = listOf(
            ref("autre", 1, 65.0, by = "Meal Announcer"),
            ref("loin", 25, 65.0),
            ref("proche", -3, 65.0),
            ref("sans-glucides", 0, null),
        )
        assertEquals("proche", matchingGlookoEntry(found, 65, at)?.id)
    }
}
