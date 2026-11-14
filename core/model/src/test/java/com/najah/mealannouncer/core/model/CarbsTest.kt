package com.najah.mealannouncer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vérifie les arrondis et l'affichage des glucides, qui partent vers CamAPS. */
class CarbsTest {

    @Test
    fun `arrondit au dixieme de gramme`() {
        assertEquals(27.5, Carbs(27.46).rounded(1), 0.0001)
        assertEquals(27.5, Carbs(27.45).rounded(1), 0.0001)
        assertEquals(28.0, Carbs(27.95).rounded(1), 0.0001)
    }

    @Test
    fun `affiche sans decimale inutile`() {
        assertEquals("22", Carbs(22.0).format())
        assertEquals("22,5", Carbs(22.5).format())
        assertEquals("40", Carbs(39.97).format())
    }

    @Test
    fun `convertit en grammes entiers pour CamAPS`() {
        assertEquals(22, Carbs(22.0).toCamapsGrams())
        // Un demi-gramme est arrondi au supérieur : on ne sous-déclare jamais un repas.
        assertEquals(40, Carbs(39.5).toCamapsGrams())
        assertEquals(40, Carbs(40.4).toCamapsGrams())
    }

    @Test
    fun `additionne deux quantites`() {
        assertEquals(40.0, (Carbs(22.0) + Carbs(18.0)).grams, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuse des glucides negatifs`() {
        Carbs.of(-1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuse des glucides non finis`() {
        Carbs.of(Double.NaN)
    }

    @Test
    fun `accepte zero`() {
        assertTrue(Carbs.of(0.0).grams == 0.0)
    }
}
