package com.najah.mealannouncer.core.model

/** Un aliment dont le calcul a échoué, conservé pour être signalé à l'utilisateur. */
data class IncompleteMealItem(
    val item: MealItem,
    val reason: CarbUnavailableReason,
    val explanation: String,
)

/**
 * Total d'un repas : les aliments calculés d'un côté, ceux qui manquent de l'autre.
 *
 * Le total ne comprend **que** les aliments calculés ; les autres sont listés dans
 * [incomplete] pour que l'écran de confirmation les affiche en clair.
 */
data class MealTotal(
    val total: Carbs,
    val resolved: List<ResolvedMealItem>,
    val incomplete: List<IncompleteMealItem>,
) {
    /** Vrai si au moins un aliment n'a pas pu être chiffré. */
    val hasIncomplete: Boolean get() = incomplete.isNotEmpty()

    /** Vrai si le repas ne contient aucun aliment chiffré. */
    val isEmpty: Boolean get() = resolved.isEmpty()

    /** Total tel qu'il sera saisi dans CamAPS FX (grammes entiers). */
    fun camapsGrams(): Int = total.toCamapsGrams()
}

/**
 * Calcule le total d'un repas à partir des aliments reconnus.
 *
 * Un aliment déjà chiffré ([MealItem.carbs]) est repris tel quel ; sinon on tente le
 * calcul avec le produit associé. Un aliment sans produit et sans valeur reste
 * incomplet : il n'est jamais compté comme zéro.
 */
object MealTotals {

    fun compute(
        intent: MealIntent,
        conversions: UnitConversions = UnitConversions(),
    ): MealTotal {
        val resolved = mutableListOf<ResolvedMealItem>()
        val incomplete = mutableListOf<IncompleteMealItem>()
        var sum = 0.0

        for (item in intent.items) {
            val computed = CarbCalculator.carbsFor(
                product = item.matchedProduct,
                quantity = item.quantity,
                unit = item.unit,
                conversions = conversions,
            )

            val line = when {
                computed is CarbResult.Computed -> ResolvedMealItem(
                    productName = item.matchedProduct?.name ?: item.normalizedProductName,
                    brand = item.matchedProduct?.brand ?: item.brand,
                    quantity = item.quantity,
                    unit = item.unit,
                    carbsPerUnit = computed.carbs.grams / item.quantity,
                    totalCarbs = computed.carbs,
                    source = item.matchedProduct?.source ?: FoodSource.MANUAL,
                    confidence = item.confidence,
                    basis = computed.basis,
                )

                // Valeur fournie par la saisie manuelle : elle fait foi.
                item.carbs != null -> ResolvedMealItem(
                    productName = item.matchedProduct?.name ?: item.normalizedProductName,
                    brand = item.matchedProduct?.brand ?: item.brand,
                    quantity = item.quantity,
                    unit = item.unit,
                    carbsPerUnit = item.carbs.grams / item.quantity,
                    totalCarbs = item.carbs,
                    source = item.matchedProduct?.source ?: FoodSource.MANUAL,
                    confidence = item.confidence,
                    basis = CarbBasis.PER_UNIT,
                )

                else -> null
            }

            if (line != null) {
                resolved += line
                sum += line.totalCarbs.grams
            } else {
                val unavailable = computed as CarbResult.Unavailable
                incomplete += IncompleteMealItem(
                    item = item,
                    reason = unavailable.reason,
                    explanation = unavailable.explanation,
                )
            }
        }

        return MealTotal(
            total = Carbs.of(sum),
            resolved = resolved,
            incomplete = incomplete,
        )
    }

    /** Somme brute de lignes déjà calculées (utile après correction manuelle). */
    fun totalOf(items: List<ResolvedMealItem>): Carbs =
        Carbs.of(items.sumOf { it.totalCarbs.grams })
}
