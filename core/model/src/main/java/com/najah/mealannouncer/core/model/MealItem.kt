package com.najah.mealannouncer.core.model

/**
 * Un aliment isolé dans une phrase dictée, avant résolution du produit.
 *
 * `carbs` reste nul tant que le produit n'a pas été trouvé et le calcul fait :
 * un aliment non résolu ne doit jamais être compté comme zéro.
 */
data class MealItem(
    val rawText: String,
    val normalizedProductName: String,
    val brand: String? = null,
    val quantity: Double = 1.0,
    val unit: MealUnit = MealUnit.UNIT,
    val matchedProduct: FoodProduct? = null,
    val carbs: Carbs? = null,
    val confidence: Float = 0f,
) {
    /** Vrai si les glucides de cet aliment sont connus. */
    fun isResolved(): Boolean = carbs != null
}
