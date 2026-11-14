package com.najah.mealannouncer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cas de référence du cahier des charges :
 * « deux biscuits Prince » = 22 g, « 200 ml de Candy Up » = 18 g, total 40 g.
 */
class CarbCalculatorTest {

    private val prince = FoodProduct(
        id = "prince",
        barcode = "3229820129488",
        name = "Biscuit Prince",
        brand = "LU",
        carbsPerUnit = 11.0,
        defaultPortionGrams = 22.0,
        source = FoodSource.USER_FAVORITE,
        verifiedByUser = true,
    )

    private val candyUp = FoodProduct(
        id = "candy-up-chocolat",
        barcode = "3245390094686",
        name = "Candy Up chocolat",
        brand = "Candy Up",
        carbsPer100ml = 9.0,
        source = FoodSource.OPEN_FOOD_FACTS_CACHE,
    )

    private fun computed(result: CarbResult): CarbResult.Computed {
        assertTrue("Résultat attendu calculé, obtenu $result", result is CarbResult.Computed)
        return result as CarbResult.Computed
    }

    private fun unavailable(result: CarbResult): CarbResult.Unavailable {
        assertTrue("Résultat attendu indisponible, obtenu $result", result is CarbResult.Unavailable)
        return result as CarbResult.Unavailable
    }

    @Test
    fun `deux biscuits valent 22 g`() {
        val result = computed(CarbCalculator.carbsFor(prince, quantity = 2.0, unit = MealUnit.UNIT))
        assertEquals(22.0, result.carbs.grams, 0.0001)
        assertEquals(CarbBasis.PER_UNIT, result.basis)
    }

    @Test
    fun `deux cents millilitres valent 18 g`() {
        val result = computed(CarbCalculator.carbsFor(candyUp, quantity = 200.0, unit = MealUnit.MILLILITER))
        assertEquals(18.0, result.carbs.grams, 0.0001)
        assertEquals(CarbBasis.PER_100_ML, result.basis)
    }

    @Test
    fun `une tasse vaut 200 ml par convention`() {
        val result = computed(CarbCalculator.carbsFor(candyUp, quantity = 1.0, unit = MealUnit.CUP))
        assertEquals(18.0, result.carbs.grams, 0.0001)
        assertEquals(200.0, result.referenceQuantity!!, 0.0001)
        assertTrue("La conversion en tasse est une convention assumée", result.assumed)
    }

    @Test
    fun `une demi-tasse vaut 9 g`() {
        val result = computed(CarbCalculator.carbsFor(candyUp, quantity = 0.5, unit = MealUnit.CUP))
        assertEquals(9.0, result.carbs.grams, 0.0001)
    }

    @Test
    fun `une quantite decimale est acceptee`() {
        val result = computed(CarbCalculator.carbsFor(prince, quantity = 2.5, unit = MealUnit.UNIT))
        assertEquals(27.5, result.carbs.grams, 0.0001)
    }

    @Test
    fun `le poids en grammes passe par les glucides pour 100 g`() {
        val pain = FoodProduct(
            id = "pain",
            barcode = null,
            name = "Pain complet",
            brand = null,
            carbsPer100g = 43.0,
        )
        val result = computed(CarbCalculator.carbsFor(pain, quantity = 60.0, unit = MealUnit.GRAM))
        assertEquals(25.8, result.carbs.grams, 0.0001)
        assertEquals(CarbBasis.PER_100_G, result.basis)
    }

    @Test
    fun `une portion utilise le poids de portion du produit`() {
        val yaourt = FoodProduct(
            id = "yaourt",
            barcode = null,
            name = "Yaourt nature",
            brand = null,
            carbsPer100g = 5.0,
            defaultPortionGrams = 125.0,
        )
        val result = computed(CarbCalculator.carbsFor(yaourt, quantity = 1.0, unit = MealUnit.PORTION))
        assertEquals(6.25, result.carbs.grams, 0.0001)
        assertTrue("Le poids de portion est une estimation du produit", result.assumed)
    }

    @Test
    fun `une portion sans taille de portion echoue clairement`() {
        val produit = FoodProduct(
            id = "inconnu",
            barcode = null,
            name = "Plat maison",
            brand = null,
            carbsPer100g = 12.0,
        )
        val result = unavailable(CarbCalculator.carbsFor(produit, quantity = 1.0, unit = MealUnit.PORTION))
        assertEquals(CarbUnavailableReason.MISSING_PORTION_SIZE, result.reason)
        assertTrue(result.explanation.isNotBlank())
    }

    @Test
    fun `un produit sans donnees de glucides ne renvoie pas zero`() {
        val eau = FoodProduct(
            id = "eau",
            barcode = null,
            name = "Eau",
            brand = null,
        )
        assertTrue(!eau.hasCarbData())

        val parUnite = unavailable(CarbCalculator.carbsFor(eau, quantity = 1.0, unit = MealUnit.UNIT))
        assertEquals(CarbUnavailableReason.MISSING_CARB_DATA, parUnite.reason)

        val auPoids = unavailable(CarbCalculator.carbsFor(eau, quantity = 100.0, unit = MealUnit.GRAM))
        assertEquals(CarbUnavailableReason.MISSING_CARB_DATA, auPoids.reason)
    }

    @Test
    fun `une tranche sans glucides par tranche echoue clairement`() {
        val pain = FoodProduct(
            id = "pain-tranche",
            barcode = null,
            name = "Pain de mie",
            brand = null,
            carbsPer100g = 48.0,
        )
        val result = unavailable(CarbCalculator.carbsFor(pain, quantity = 1.0, unit = MealUnit.SLICE))
        assertEquals(CarbUnavailableReason.MISSING_CARB_DATA, result.reason)
    }

    @Test
    fun `une unite inconnue est refusee`() {
        val result = unavailable(CarbCalculator.carbsFor(prince, quantity = 1.0, unit = MealUnit.UNKNOWN))
        assertEquals(CarbUnavailableReason.UNSUPPORTED_UNIT, result.reason)
    }

    @Test
    fun `un produit absent est signale`() {
        val result = unavailable(CarbCalculator.carbsFor(null, quantity = 1.0, unit = MealUnit.UNIT))
        assertEquals(CarbUnavailableReason.MISSING_PRODUCT, result.reason)
    }

    @Test
    fun `une quantite nulle ou negative est refusee`() {
        assertEquals(
            CarbUnavailableReason.INVALID_QUANTITY,
            unavailable(CarbCalculator.carbsFor(prince, quantity = 0.0, unit = MealUnit.UNIT)).reason,
        )
        assertEquals(
            CarbUnavailableReason.INVALID_QUANTITY,
            unavailable(CarbCalculator.carbsFor(prince, quantity = -2.0, unit = MealUnit.UNIT)).reason,
        )
    }

    @Test
    fun `une tasse sans glucides pour 100 ml echoue au lieu d inventer`() {
        val boisson = FoodProduct(
            id = "boisson",
            barcode = null,
            name = "Boisson",
            brand = null,
            carbsPer100g = 10.0, // densité inconnue : on refuse la conversion
        )
        val result = unavailable(CarbCalculator.carbsFor(boisson, quantity = 1.0, unit = MealUnit.CUP))
        assertEquals(CarbUnavailableReason.MISSING_CARB_DATA, result.reason)
    }

    @Test
    fun `deux biscuits se convertissent par le libelle de portion`() {
        // « 20 g, un biscuit » : la portion pèse 20 g et compte un biscuit.
        val vanille = FoodProduct(
            id = "vanille",
            barcode = null,
            name = "Prince Goût Vanille",
            brand = "LU",
            carbsPer100g = 70.0,
            defaultPortionGrams = 20.0,
            servingLabel = "20 g, un biscuit",
        )
        val result = computed(CarbCalculator.carbsFor(vanille, quantity = 2.0, unit = MealUnit.UNIT))
        // Deux biscuits de 20 g = 40 g, donc 40 % de 70 g = 28 g.
        assertEquals(28.0, result.carbs.grams, 0.0001)
        assertEquals(CarbBasis.PER_100_G, result.basis)
        assertEquals(40.0, result.referenceQuantity ?: 0.0, 0.0001)
        assertTrue("Une conversion de portion est une convention", result.assumed)
    }

    @Test
    fun `deux biscuits d'un produit dont la fiche dit un biscuit 20 g`() {
        // Fiche réelle (Prince Biscuits au blé complet fourrés goût tout choco, LU) : le libellé
        // écrit l'article **avant** le poids, en toutes lettres. C'est le même renseignement que
        // « 20 g, un biscuit », écrit dans l'autre sens.
        val toutChoco = FoodProduct(
            id = "tout-choco",
            barcode = "7622210450319",
            name = "Prince Biscuits au blé complet fourrés goût tout choco",
            brand = "LU",
            carbsPer100g = 69.0,
            defaultPortionGrams = 20.0,
            servingLabel = "Un biscuit 20 g",
        )
        val result = computed(CarbCalculator.carbsFor(toutChoco, quantity = 2.0, unit = MealUnit.UNIT))
        // Deux biscuits de 20 g = 40 g, donc 40 % de 69 g = 27,6 g.
        assertEquals(27.6, result.carbs.grams, 0.0001)
        assertEquals(40.0, result.referenceQuantity ?: 0.0, 0.0001)
        assertTrue(result.assumed)
    }

    @Test
    fun `un libelle qui nomme deux articles par portion`() {
        // « 2 biscuits (25 g) » : un biscuit pèse 12,5 g.
        val paquet = FoodProduct(
            id = "paquet",
            barcode = null,
            name = "Prince",
            brand = "LU",
            carbsPer100g = 70.0,
            defaultPortionGrams = 25.0,
            servingLabel = "2 biscuits (25 g)",
        )
        val result = computed(CarbCalculator.carbsFor(paquet, quantity = 2.0, unit = MealUnit.UNIT))
        assertEquals(17.5, result.carbs.grams, 0.0001)
    }

    @Test
    fun `un libelle sans article ne permet pas de convertir une unite`() {
        // « 20 g » ne dit pas combien d'articles : on refuse plutôt que d'inventer.
        val paquet = FoodProduct(
            id = "paquet",
            barcode = null,
            name = "Prince",
            brand = "LU",
            carbsPer100g = 69.0,
            defaultPortionGrams = 20.0,
            servingLabel = "20g",
        )
        val result = unavailable(CarbCalculator.carbsFor(paquet, quantity = 2.0, unit = MealUnit.UNIT))
        assertEquals(CarbUnavailableReason.MISSING_PORTION_SIZE, result.reason)
        assertTrue(result.explanation.contains("poids en grammes"))
    }
}
