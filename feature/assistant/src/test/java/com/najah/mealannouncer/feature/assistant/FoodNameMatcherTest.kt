package com.najah.mealannouncer.feature.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le filtre qui empêche de chiffrer un aliment avec les glucides d'un autre.
 *
 * Le cas « banane » a été observé sur l'appareil : Open Food Facts a rendu un fromage blanc
 * en premier résultat. Ces tests le figent.
 */
class FoodNameMatcherTest {

    @Test
    fun `un produit hors sujet est refusé`() {
        assertFalse(
            FoodNameMatcher.isPlausible(
                "banane",
                "Fromage Blanc Nature (Milky Food Professional)",
            ),
        )
    }

    @Test
    fun `un produit qui contient l'aliment est accepté`() {
        assertTrue(FoodNameMatcher.isPlausible("banane", "Banane plantain"))
        assertTrue(FoodNameMatcher.isPlausible("banane", "Bananes"))
    }

    @Test
    fun `un nom plus court que la demande reste accepté`() {
        assertTrue(FoodNameMatcher.isPlausible("riz basmati cuit", "Riz basmati"))
    }

    @Test
    fun `un produit de marque ne chiffre pas un plat composé`() {
        // Observé sur l'appareil : « tajine de poulet aux olives » rendait « Tajine (Picard) »,
        // un plat préparé complet, en plus du poulet et des olives — les mêmes glucides
        // comptés deux fois, et un plat de marque à la place d'une recette maison.
        assertFalse(FoodNameMatcher.isPlausible("tajine de poulet aux olives", "Tajine"))
        assertFalse(FoodNameMatcher.isPlausible("salade de riz", "Riz"))
        assertTrue(FoodNameMatcher.isPlausible("tajine de poulet aux olives", "Tajine de poulet"))
    }

    @Test
    fun `un produit qui ne partage qu'un mot sur trois est refusé`() {
        assertFalse(FoodNameMatcher.isPlausible("riz basmati cuit", "Riz au lait"))
    }

    @Test
    fun `les accents et la casse ne changent rien`() {
        assertTrue(FoodNameMatcher.isPlausible("crème fraîche", "CREME FRAICHE 30%"))
        assertTrue(FoodNameMatcher.isPlausible("pêche", "Peche blanche"))
    }

    @Test
    fun `les mots de liaison sont ignorés`() {
        assertTrue(FoodNameMatcher.isPlausible("pain complet", "Pain de mie complet"))
        assertFalse(FoodNameMatcher.isPlausible("de la", "Pain complet"))
    }

    @Test
    fun `un nom qui ressemble sans contenir le mot est refusé`() {
        assertFalse(FoodNameMatcher.isPlausible("banane", "Banania"))
    }

    @Test
    fun `un nom vide ne correspond à rien`() {
        assertFalse(FoodNameMatcher.isPlausible("", "Banane"))
        assertFalse(FoodNameMatcher.isPlausible("Banane", ""))
    }
}
