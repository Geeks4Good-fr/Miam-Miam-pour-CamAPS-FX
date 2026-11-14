package com.najah.mealannouncer.core.model

/**
 * Résultat d'un calcul de glucides : soit une valeur exploitable, soit une raison
 * précise de ne pas pouvoir la donner.
 *
 * On ne renvoie jamais 0 en cas d'échec : un aliment non calculé doit être signalé,
 * pas compté comme sans glucides.
 */
sealed interface CarbResult {

    /** Le calcul a abouti. */
    data class Computed(
        val carbs: Carbs,
        val basis: CarbBasis,
        /** Quantité de référence réellement utilisée (grammes ou millilitres). */
        val referenceQuantity: Double?,
        /** Vrai si une convention a été appliquée (tasse = 200 ml, portion par défaut). */
        val assumed: Boolean = false,
    ) : CarbResult

    /** Le calcul est impossible ; [explanation] est affichable tel quel. */
    data class Unavailable(
        val reason: CarbUnavailableReason,
        val explanation: String,
    ) : CarbResult
}

/** Pourquoi un calcul de glucides n'a pas pu être fait. */
enum class CarbUnavailableReason {
    /** Aucun produit n'a été trouvé pour cet aliment. */
    MISSING_PRODUCT,

    /** Le produit existe mais ne porte aucune donnée de glucides exploitable. */
    MISSING_CARB_DATA,

    /** Le produit n'indique ni poids ni volume de portion. */
    MISSING_PORTION_SIZE,

    /** L'unité employée ne permet aucun calcul (unité inconnue, tranche sans donnée). */
    UNSUPPORTED_UNIT,

    /** Quantité nulle, négative ou non finie. */
    INVALID_QUANTITY,
}

/**
 * Règles pures de calcul des glucides.
 *
 * Aucune dépendance Android, aucune entrée-sortie : tout est testable sur la JVM.
 */
object CarbCalculator {

    /**
     * Calcule les glucides d'un aliment.
     *
     * @param product produit résolu, ou null si aucun produit n'a été trouvé.
     * @param quantity quantité exprimée dans [unit] (2 biscuits, 200 ml, 1 tranche).
     * @param unit unité de la quantité.
     */
    fun carbsFor(
        product: FoodProduct?,
        quantity: Double,
        unit: MealUnit,
        conversions: UnitConversions = UnitConversions(),
    ): CarbResult {
        if (!quantity.isFinite() || quantity <= 0.0) {
            return CarbResult.Unavailable(
                CarbUnavailableReason.INVALID_QUANTITY,
                "Quantité invalide : $quantity",
            )
        }
        if (product == null) {
            return CarbResult.Unavailable(
                CarbUnavailableReason.MISSING_PRODUCT,
                "Aucun produit reconnu : il faut le choisir ou saisir les glucides à la main.",
            )
        }

        return when (unit) {
            MealUnit.GRAM -> perHundred(
                perHundred = product.carbsPer100g,
                quantity = quantity,
                unit = MealUnit.GRAM,
                basis = CarbBasis.PER_100_G,
            )

            MealUnit.MILLILITER -> perHundred(
                perHundred = product.carbsPer100ml,
                quantity = quantity,
                unit = MealUnit.MILLILITER,
                basis = CarbBasis.PER_100_ML,
            )

            MealUnit.CUP -> {
                val milliliters = conversions.toMilliliters(quantity, unit, product)
                if (milliliters == null) {
                    unsupported(product, unit)
                } else {
                    perHundred(
                        perHundred = product.carbsPer100ml,
                        quantity = milliliters,
                        unit = MealUnit.MILLILITER,
                        basis = CarbBasis.PER_100_ML,
                        assumed = conversions.usesConvention(unit),
                    )
                }
            }

            MealUnit.PORTION -> portion(product, quantity, conversions)

            MealUnit.SLICE, MealUnit.UNIT -> perUnit(product, quantity, unit)

            MealUnit.UNKNOWN -> unsupported(product, unit)
        }
    }

    /** Glucides pour 100 g ou 100 ml. */
    private fun perHundred(
        perHundred: Double?,
        quantity: Double,
        unit: MealUnit,
        basis: CarbBasis,
        assumed: Boolean = false,
    ): CarbResult {
        val reference = if (perHundred == null) null else perHundred
        if (reference == null) {
            return CarbResult.Unavailable(
                CarbUnavailableReason.MISSING_CARB_DATA,
                "Le produit n'indique pas ses glucides pour 100 ${if (unit == MealUnit.GRAM) "g" else "ml"}.",
            )
        }
        val grams = reference * quantity / 100.0
        return CarbResult.Computed(
            carbs = Carbs.of(grams),
            basis = basis,
            referenceQuantity = quantity,
            assumed = assumed,
        )
    }

    /**
     * Glucides par unité, tranche ou portion décomptée.
     *
     * Une fiche donne rarement des glucides « par biscuit ». En revanche elle dit souvent le
     * poids d'une portion **et** combien d'articles elle contient — « 2 biscuits (25 g) »,
     * « 20 g, un biscuit ». Dans ce cas « deux biscuits » se convertit : deux articles, leur
     * poids, puis les glucides aux 100 g. C'est exactement ce que dit quelqu'un qui parle de
     * biscuits, et sans cette conversion l'application lui demanderait des grammes qu'il ne
     * connaît pas.
     */
    private fun perUnit(product: FoodProduct, quantity: Double, unit: MealUnit): CarbResult {
        product.carbsPerUnit?.let { perUnit ->
            return CarbResult.Computed(
                carbs = Carbs.of(perUnit * quantity),
                basis = CarbBasis.PER_UNIT,
                referenceQuantity = quantity,
            )
        }

        val gramsPerItem = ServingText.gramsPerItem(product.servingLabel, product.defaultPortionGrams)
        val per100g = product.carbsPer100g
        if (gramsPerItem != null && per100g != null) {
            val grams = gramsPerItem * quantity
            return CarbResult.Computed(
                carbs = Carbs.of(per100g * grams / 100.0),
                basis = CarbBasis.PER_100_G,
                referenceQuantity = grams,
                assumed = true,
            )
        }

        return CarbResult.Unavailable(
            reason = if (gramsPerItem == null && product.defaultPortionGrams != null) {
                // La portion a un poids, mais la fiche ne dit pas combien d'articles elle compte :
                // impossible de savoir ce que pèse « un biscuit ».
                CarbUnavailableReason.MISSING_PORTION_SIZE
            } else {
                CarbUnavailableReason.MISSING_CARB_DATA
            },
            explanation = if (gramsPerItem == null && product.defaultPortionGrams != null) {
                "Le produit donne le poids d'une portion mais pas le nombre d'articles : " +
                    "indiquez un poids en grammes."
            } else {
                "Le produit n'indique pas ses glucides par ${unitLabel(unit)}."
            },
        )
    }

    /**
     * Une portion peut être calculée de trois façons, dans cet ordre :
     * glucides par portion → poids de portion + glucides pour 100 g → volume de portion
     * + glucides pour 100 ml. Sinon, on explique ce qui manque.
     */
    private fun portion(
        product: FoodProduct,
        quantity: Double,
        conversions: UnitConversions,
    ): CarbResult {
        product.carbsPerUnit?.let { perUnit ->
            return CarbResult.Computed(
                carbs = Carbs.of(perUnit * quantity),
                basis = CarbBasis.PER_UNIT,
                referenceQuantity = quantity,
            )
        }

        val grams = conversions.toGrams(quantity, MealUnit.PORTION, product)
        if (grams != null && product.carbsPer100g != null) {
            return CarbResult.Computed(
                carbs = Carbs.of(product.carbsPer100g * grams / 100.0),
                basis = CarbBasis.PER_100_G,
                referenceQuantity = grams,
                assumed = true,
            )
        }

        val milliliters = conversions.toMilliliters(quantity, MealUnit.PORTION, product)
        if (milliliters != null && product.carbsPer100ml != null) {
            return CarbResult.Computed(
                carbs = Carbs.of(product.carbsPer100ml * milliliters / 100.0),
                basis = CarbBasis.PER_100_ML,
                referenceQuantity = milliliters,
                assumed = true,
            )
        }

        val hasPortionSize = product.defaultPortionGrams != null || product.defaultPortionMl != null
        return CarbResult.Unavailable(
            reason = if (hasPortionSize) {
                CarbUnavailableReason.MISSING_CARB_DATA
            } else {
                CarbUnavailableReason.MISSING_PORTION_SIZE
            },
            explanation = if (hasPortionSize) {
                "Le produit indique une portion mais pas ses glucides pour 100 g/ml."
            } else {
                "Le produit n'indique pas le poids d'une portion : donnez un poids ou un volume."
            },
        )
    }

    private fun unsupported(product: FoodProduct, unit: MealUnit): CarbResult =
        CarbResult.Unavailable(
            CarbUnavailableReason.UNSUPPORTED_UNIT,
            "Unité « ${unitLabel(unit)} » non convertible pour ${product.displayName()}.",
        )

    private fun unitLabel(unit: MealUnit): String = when (unit) {
        MealUnit.UNIT -> "unité"
        MealUnit.GRAM -> "gramme"
        MealUnit.MILLILITER -> "millilitre"
        MealUnit.PORTION -> "portion"
        MealUnit.CUP -> "tasse"
        MealUnit.SLICE -> "tranche"
        MealUnit.UNKNOWN -> "inconnue"
    }
}
