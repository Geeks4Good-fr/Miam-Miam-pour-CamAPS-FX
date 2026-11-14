package com.najah.mealannouncer.core.model

/**
 * Un aliment dont les glucides sont effectivement calculés : c'est la ligne
 * affichée dans l'écran de relecture (« Prince ×2 : 22 g »).
 */
data class ResolvedMealItem(
    val productName: String,
    val brand: String?,
    val quantity: Double,
    val unit: MealUnit,
    /** Glucides pour une unité de la base retenue (par unité, par 100 g ou par 100 ml). */
    val carbsPerUnit: Double,
    val totalCarbs: Carbs,
    val source: FoodSource,
    val confidence: Float,
    /** Base du calcul, utile pour expliquer le chiffre à l'utilisateur. */
    val basis: CarbBasis,
) {
    /** Libellé court : « Prince ×2 — 22 g ». */
    fun summary(): String = "$productName ×${formatQuantity(quantity)} — ${totalCarbs.format()} g"

    /** Nom complet : « Biscuit Prince chocolat (LU) » quand la marque est connue. */
    fun displayName(): String =
        if (brand.isNullOrBlank()) productName else "$productName ($brand)"

    /**
     * Libellé très court, pour une note ou une liste : « Prince ×2 », « Candy Up 200 ml »,
     * « Pain complet 30 g ». La quantité est lisible sans le total, qui est déjà ailleurs.
     */
    fun shortLabel(): String {
        val amount = formatQuantity(quantity)
        return when (unit) {
            MealUnit.GRAM -> "$productName $amount g"
            MealUnit.MILLILITER -> "$productName $amount ml"
            MealUnit.CUP -> "$productName ×$amount tasse"
            MealUnit.UNIT, MealUnit.PORTION, MealUnit.SLICE, MealUnit.UNKNOWN ->
                "$productName ×$amount"
        }
    }
}

/** Base sur laquelle le calcul des glucides a été fait. */
enum class CarbBasis {
    /** Glucides par unité, portion ou tranche. */
    PER_UNIT,

    /** Glucides pour 100 grammes. */
    PER_100_G,

    /** Glucides pour 100 millilitres. */
    PER_100_ML,
}

/**
 * Affiche une quantité sans décimale inutile : « 2 » et non « 2,0 »,
 * mais « 2,5 » quand la précision existe.
 */
internal fun formatQuantity(quantity: Double): String {
    val rounded = kotlin.math.round(quantity * 100.0) / 100.0
    return if (rounded == kotlin.math.floor(rounded)) {
        rounded.toLong().toString()
    } else {
        String.format(java.util.Locale.ROOT, "%s", rounded.toString()).replace('.', ',')
    }
}
