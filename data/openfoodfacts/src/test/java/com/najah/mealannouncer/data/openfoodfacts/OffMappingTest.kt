package com.najah.mealannouncer.data.openfoodfacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vérifie le filtrage des valeurs d'Open Food Facts.
 *
 * C'est ici que se joue la sûreté des chiffres : la base collaborative contient des
 * valeurs aberrantes, et une valeur aberrante recopiée dans CamAPS est pire qu'une valeur
 * manquante.
 */
class OffMappingTest {

    @Test
    fun `garde une valeur plausible`() {
        assertEquals(68.0, OffMapping.validCarbsPer100(68.0)!!, 0.0)
    }

    @Test
    fun `conserve zero glucide`() {
        // Zéro est une donnée : l'eau contient bien 0 glucide.
        assertEquals(0.0, OffMapping.validCarbsPer100(0.0)!!, 0.0)
    }

    @Test
    fun `refuse une valeur absente, negative ou impossible`() {
        assertNull(OffMapping.validCarbsPer100(null))
        assertNull(OffMapping.validCarbsPer100(-3.0))
        assertNull(OffMapping.validCarbsPer100(Double.NaN))
        assertNull(OffMapping.validCarbsPer100(Double.POSITIVE_INFINITY))
        // 250 g de glucides pour 100 g de produit : impossible.
        assertNull(OffMapping.validCarbsPer100(250.0))
        // 100 g pour 100 g reste la limite haute admise (sucre pur).
        assertEquals(100.0, OffMapping.validCarbsPer100(100.0)!!, 0.0)
    }

    @Test
    fun `deduit les glucides d'une portion`() {
        // Biscuit Prince : 68 g pour 100 g, portion de 16,5 g → 11,22 g.
        assertEquals(11.22, OffMapping.carbsForServing(68.0, 16.5)!!, 0.001)
        // Candy Up : 9 g pour 100 ml, portion de 200 ml → 18 g.
        assertEquals(18.0, OffMapping.carbsForServing(9.0, 200.0)!!, 0.001)
    }

    @Test
    fun `refuse une portion absurde`() {
        assertNull(OffMapping.carbsForServing(68.0, null))
        assertNull(OffMapping.carbsForServing(68.0, 0.0))
        assertNull(OffMapping.carbsForServing(68.0, -16.5))
        assertNull(OffMapping.carbsForServing(null, 16.5))
        // 100 kg de portion : la fiche est fausse.
        assertNull(OffMapping.carbsForServing(68.0, 100_000.0))
    }

    @Test
    fun `reconnait une portion en volume`() {
        assertTrue(OffMapping.servingIsVolume("ml"))
        assertTrue(OffMapping.servingIsVolume(" ML "))
        assertTrue(OffMapping.servingIsVolume("cl"))
        assertTrue(OffMapping.servingIsVolume("L"))
        assertFalse(OffMapping.servingIsVolume("g"))
        assertFalse(OffMapping.servingIsVolume(null))
    }

    @Test
    fun `convertit les unites de portion en base`() {
        assertEquals(16.5, OffMapping.servingToBaseQuantity(16.5, "g")!!, 0.0)
        assertEquals(200.0, OffMapping.servingToBaseQuantity(20.0, "cl")!!, 0.0)
        assertEquals(1000.0, OffMapping.servingToBaseQuantity(1.0, "l")!!, 0.0)
        assertEquals(33.0, OffMapping.servingToBaseQuantity(33.0, null)!!, 0.0)
        // Unité inconnue : on ne devine pas.
        assertNull(OffMapping.servingToBaseQuantity(1.0, "louche"))
    }

    @Test
    fun `nettoie les noms et les marques`() {
        assertEquals("Biscuit Prince chocolat", OffMapping.cleanName("  Biscuit   Prince chocolat "))
        assertNull(OffMapping.cleanName("   "))
        assertNull(OffMapping.cleanName(null))
        assertEquals("LU", OffMapping.cleanBrand("LU, Mondelez"))
        assertNull(OffMapping.cleanBrand(""))
        assertNull(OffMapping.cleanBrand(null))
    }

    @Test
    fun `tronque un nom demesure`() {
        val long = "a".repeat(500)
        assertEquals(200, OffMapping.cleanName(long)!!.length)
    }
}
