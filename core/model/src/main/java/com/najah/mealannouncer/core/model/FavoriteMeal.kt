package com.najah.mealannouncer.core.model

import java.time.Instant

/**
 * Une ligne d'un repas favori : le produit et la quantité, tels qu'ils ont été annoncés.
 *
 * Les valeurs nutritionnelles sont **recopiées** dans le favori, et pas seulement référencées :
 * un produit peut disparaître du cache Open Food Facts, et un favori doit rester annonçable des
 * mois plus tard, même hors ligne.
 */
data class FavoriteMealLine(
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val quantity: Double,
    val unit: MealUnit,
    val carbsPer100g: Double? = null,
    val carbsPer100ml: Double? = null,
    val carbsPerUnit: Double? = null,
    val portionGrams: Double? = null,
    val portionMl: Double? = null,
    val servingLabel: String? = null,
    val packageGrams: Double? = null,
) {
    /** Le produit reconstitué, pour recalculer comme lors de la première annonce. */
    fun toProduct(idSuffix: String = "fav"): FoodProduct = FoodProduct(
        id = "$idSuffix-${barcode ?: name.hashCode()}",
        barcode = barcode,
        name = name,
        brand = brand,
        carbsPer100g = carbsPer100g,
        carbsPer100ml = carbsPer100ml,
        carbsPerUnit = carbsPerUnit,
        defaultPortionGrams = portionGrams,
        defaultPortionMl = portionMl,
        servingLabel = servingLabel,
        packageGrams = packageGrams,
        source = FoodSource.USER_FAVORITE,
        verifiedByUser = true,
    )

    /** Glucides de cette ligne, ou `null` si le calcul est impossible. */
    fun carbs(): Carbs? =
        (CarbCalculator.carbsFor(toProduct(), quantity, unit) as? CarbResult.Computed)?.carbs
}

/**
 * Un repas favori : le même petit-déjeuner, le même goûter, annoncé en un geste.
 *
 * La demande de l'utilisateur est précise : « des annonces rapides puisque fréquemment utilisées ».
 * Un favori porte donc **les lignes complètes** et pas un simple total, afin que le détail
 * par produit reste affiché et corrigeable après rechargement.
 */
data class FavoriteMeal(
    val id: String,
    val name: String,
    val lines: List<FavoriteMealLine>,
    val createdAt: Instant = Instant.now(),
    val lastUsedAt: Instant? = null,
    val uses: Int = 0,
) {
    /** Total recalculé à la lecture : jamais une valeur figée qui pourrait mentir. */
    val total: Carbs
        get() = Carbs.of(lines.sumOf { it.carbs()?.grams ?: 0.0 })

    /** Vrai si au moins une ligne sait se chiffrer. */
    val isAnnounceable: Boolean get() = total.grams > 0.0

    /** La description courte, telle qu'elle part vers CamAPS et le Carnet. */
    fun description(): String = lines.joinToString(" + ") { line ->
        val quantity = if (line.quantity == line.quantity.toLong().toDouble()) {
            line.quantity.toLong().toString()
        } else {
            line.quantity.toString().replace('.', ',')
        }
        val unit = when (line.unit) {
            MealUnit.GRAM -> "g"
            MealUnit.MILLILITER -> "ml"
            MealUnit.PORTION -> "portion(s)"
            MealUnit.CUP -> "tasse(s)"
            MealUnit.SLICE -> "tranche(s)"
            MealUnit.UNIT, MealUnit.UNKNOWN -> ""
        }
        "${
            if (line.brand.isNullOrBlank()) line.name else "${line.name} (${line.brand})"
        } $quantity $unit".trim()
    }

    /** Les lignes au format des recettes de l'assistant, sans dépendre de `feature`. */
    fun markUsed(now: Instant = Instant.now()): FavoriteMeal =
        copy(lastUsedAt = now, uses = uses + 1)
}
