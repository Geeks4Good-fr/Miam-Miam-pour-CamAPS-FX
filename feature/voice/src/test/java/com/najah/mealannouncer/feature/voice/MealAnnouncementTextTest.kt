package com.najah.mealannouncer.feature.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class MealAnnouncementTextTest {

    @Test
    fun `phrase nominale au pluriel`() {
        assertEquals(
            "Repas annoncé : 62 grammes de glucides.",
            MealAnnouncementText.forCarbs(62.0),
        )
    }

    @Test
    fun `un seul gramme est au singulier`() {
        assertEquals("Repas annoncé : 1 gramme de glucides.", MealAnnouncementText.forCarbs(1.0))
    }

    @Test
    fun `zero se dit en toutes lettres`() {
        assertEquals("Repas annoncé : aucun gramme de glucides.", MealAnnouncementText.forCarbs(0.0))
    }

    @Test
    fun `les glucides sont arrondis au gramme`() {
        assertEquals("Repas annoncé : 40 grammes de glucides.", MealAnnouncementText.forCarbs(40.4))
        assertEquals("Repas annoncé : 41 grammes de glucides.", MealAnnouncementText.forCarbs(40.6))
    }

    @Test
    fun `une valeur negative ou non finie retombe sur zero`() {
        assertEquals("Repas annoncé : aucun gramme de glucides.", MealAnnouncementText.forCarbs(-5.0))
        assertEquals("Repas annoncé : aucun gramme de glucides.", MealAnnouncementText.forCarbs(Double.NaN))
    }

    @Test
    fun `un prefixe personnalise est respecte`() {
        assertEquals(
            "Repas enregistré : 30 grammes de glucides.",
            MealAnnouncementText.forCarbs(30.0, prefix = "Repas enregistré"),
        )
    }

    @Test
    fun `un prefixe vide retombe sur le defaut`() {
        assertEquals(
            "Repas annoncé : 30 grammes de glucides.",
            MealAnnouncementText.forCarbs(30.0, prefix = "   "),
        )
    }
}
