package com.najah.mealannouncer.core.model

import java.time.Instant

/** Provenance des valeurs nutritionnelles d'un produit. */
enum class FoodSource {
    /** Favori saisi ou validé par l'utilisateur : la source la plus fiable. */
    USER_FAVORITE,

    /** Produit déjà récupéré depuis Open Food Facts et conservé localement. */
    OPEN_FOOD_FACTS_CACHE,

    /** Produit récupéré en ligne à l'instant. */
    OPEN_FOOD_FACTS_ONLINE,

    /** Valeurs saisies à la main par l'utilisateur. */
    MANUAL,

    /**
     * Banque de portions en images : base embarquée (Nutrition5k, Ciqual) ou paquet importé par
     * l'utilisateur (`docs/PORTIONS.md`). Consultée avant Open Food Facts.
     */
    PORTION_BANK,
}

/**
 * Un produit alimentaire et ses données nutritionnelles.
 *
 * Tous les champs « par 100 g/ml » sont facultatifs : beaucoup de produits
 * n'exposent qu'une seule base, et certains n'ont aucune donnée exploitable.
 * C'est [CarbCalculator] qui décide si le calcul est possible.
 */
data class FoodProduct(
    val id: String,
    val barcode: String?,
    val name: String,
    val brand: String?,
    val carbsPer100g: Double? = null,
    val carbsPer100ml: Double? = null,
    val carbsPerUnit: Double? = null,
    val defaultPortionGrams: Double? = null,
    val defaultPortionMl: Double? = null,
    /**
     * Libellé de portion tel qu'il est écrit sur la fiche (« 2 biscuits (25 g) »).
     *
     * C'est lui, et lui seul, qui permet de convertir « deux biscuits » en grammes : sans le
     * nombre d'articles, la taille de portion ne dit pas combien pèse un biscuit. Il n'est
     * pas conservé dans le cache Room (les colonnes n'y sont pas) : un produit relu depuis le
     * cache perd cette précision, et l'application redemande alors un poids au lieu de
     * deviner.
     */
    val servingLabel: String? = null,
    /** Poids du paquet entier (« 200 g », « 1 kg »), pour « un tiers du paquet ». */
    val packageGrams: Double? = null,
    /**
     * Photo de face du produit, telle que la fiche Open Food Facts la donne : elle laisse
     * l'utilisateur reconnaître d'un coup d'œil l'article chiffré. Pas conservée dans le cache
     * Room ; `null` quand la fiche n'en a pas — jamais une photo d'un autre produit.
     */
    val imageUrl: String? = null,
    val source: FoodSource = FoodSource.MANUAL,
    val verifiedByUser: Boolean = false,
    val updatedAt: Instant = Instant.EPOCH,
) {
    /** Nom affichable : « Prince (LU) » quand la marque est connue. */
    fun displayName(): String = if (brand.isNullOrBlank()) name else "$name ($brand)"

    /** Vrai si au moins une base de calcul est disponible. */
    fun hasCarbData(): Boolean =
        carbsPer100g != null || carbsPer100ml != null || carbsPerUnit != null
}
