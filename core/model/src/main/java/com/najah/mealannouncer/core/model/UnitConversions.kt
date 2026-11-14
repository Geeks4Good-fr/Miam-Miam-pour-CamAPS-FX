package com.najah.mealannouncer.core.model

/**
 * Conversions entre unités « de cuisine » et unités de mesure, quand les données
 * existent.
 *
 * - Une tasse est un volume conventionnel : **200 ml** (valeur retenue par le cahier
 *   des charges pour « une tasse de Candy Up »). C'est une convention assumée, pas une
 *   mesure : l'écran de confirmation doit rester modifiable.
 * - Une portion n'est convertible que si le produit indique son poids ou son volume.
 * - Une tranche n'est **jamais** convertie en grammes : son poids dépend du pain ou du
 *   fromage, et une valeur inventée fausserait l'annonce. Elle n'est calculable que si
 *   le produit donne directement les glucides par tranche.
 */
data class UnitConversions(
    val millilitersPerCup: Double = DEFAULT_ML_PER_CUP,
) {
    /** Convertit une quantité en millilitres, ou null si la conversion est impossible. */
    fun toMilliliters(quantity: Double, unit: MealUnit, product: FoodProduct?): Double? = when (unit) {
        MealUnit.MILLILITER -> quantity
        MealUnit.CUP -> quantity * millilitersPerCup
        MealUnit.PORTION -> product?.defaultPortionMl?.let { quantity * it }
        else -> null
    }

    /** Convertit une quantité en grammes, ou null si la conversion est impossible. */
    fun toGrams(quantity: Double, unit: MealUnit, product: FoodProduct?): Double? = when (unit) {
        MealUnit.GRAM -> quantity
        MealUnit.PORTION -> product?.defaultPortionGrams?.let { quantity * it }
        else -> null
    }

    /** Vrai si la conversion en millilitres repose sur une convention et non sur une mesure. */
    fun usesConvention(unit: MealUnit): Boolean = unit == MealUnit.CUP

    companion object {
        /** Contenance d'une tasse, en millilitres. */
        const val DEFAULT_ML_PER_CUP = 200.0
    }
}
