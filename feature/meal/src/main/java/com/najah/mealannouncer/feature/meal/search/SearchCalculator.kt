package com.najah.mealannouncer.feature.meal.search

import com.najah.mealannouncer.core.model.CarbCalculator
import com.najah.mealannouncer.core.model.CarbResult
import com.najah.mealannouncer.core.model.Carbs
import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.core.model.MealUnit
import com.najah.mealannouncer.core.model.ServingText

/**
 * La façon de chiffrer une ligne de la recherche manuelle.
 *
 * Un paquet de biscuits ne se pèse pas comme une boisson ni comme une pomme : le calculateur
 * proposé à l'utilisateur dépend donc du **type de produit**, et non d'un champ unique. C'est
 * la demande du 14/11/2026 (point 2) : « chacun un calculateur de glucide par rapport au type
 * de produits ».
 */
enum class CalcMode {
    /** Au poids, sur la base des glucides pour 100 g. */
    PER_100G,

    /** Au volume, sur la base des glucides pour 100 ml. */
    PER_100ML,

    /** À la pièce, quand la fiche donne les glucides d'une pièce (ou sa taille). */
    PER_UNIT,

    /** À la portion, quand la fiche en donne une taille. */
    PER_PORTION,

    /** Au paquet entier (ou à une fraction), quand son poids est connu. */
    PACKAGE,
}

/**
 * Règles pures du calculateur de la recherche manuelle.
 *
 * Aucune dépendance Android : tout est vérifiable sur la JVM. Le calcul lui-même reste confié
 * à [CarbCalculator] — une seule règle de glucides dans toute l'application.
 */
object SearchCalculator {

    /** Les calculateurs qui ont un sens pour ce produit, dans l'ordre où les proposer. */
    fun modesFor(product: FoodProduct): List<CalcMode> = buildList {
        val perUnitGrams = ServingText.gramsPerItem(product.servingLabel, product.defaultPortionGrams)
        if (product.carbsPerUnit != null || (perUnitGrams != null && product.carbsPer100g != null)) {
            add(CalcMode.PER_UNIT)
        }
        if (product.defaultPortionGrams != null || product.defaultPortionMl != null) {
            add(CalcMode.PER_PORTION)
        }
        if (product.carbsPer100g != null) add(CalcMode.PER_100G)
        if (product.carbsPer100ml != null) add(CalcMode.PER_100ML)
        val packageGrams = product.packageGrams
        if (packageGrams != null && packageGrams > 0.0) add(CalcMode.PACKAGE)
    }

    /** Le calculateur retenu par défaut : le plus proche de la façon dont on désigne le produit. */
    fun defaultMode(product: FoodProduct): CalcMode? = modesFor(product).firstOrNull()

    /** La quantité de départ pour ce calculateur : « 1 pièce », « 100 g », « 1 portion ». */
    fun defaultQuantity(product: FoodProduct, mode: CalcMode): Double = when (mode) {
        CalcMode.PER_UNIT -> 1.0
        CalcMode.PER_PORTION -> 1.0
        CalcMode.PACKAGE -> 1.0
        CalcMode.PER_100G -> product.defaultPortionGrams ?: 100.0
        CalcMode.PER_100ML -> product.defaultPortionMl ?: 100.0
    }

    /** L'unité de quantité affichée à côté du champ. */
    fun unitOf(mode: CalcMode): MealUnit = when (mode) {
        CalcMode.PER_100G, CalcMode.PACKAGE -> MealUnit.GRAM
        CalcMode.PER_100ML -> MealUnit.MILLILITER
        CalcMode.PER_UNIT -> MealUnit.UNIT
        CalcMode.PER_PORTION -> MealUnit.PORTION
    }

    /**
     * Les grammes (ou millilitres) réellement consommés, pour la formule et le journal.
     *
     * Rend `null` quand la conversion n'est pas possible : un paquet sans poids connu, une pièce
     * sans taille. Les glucides, eux, restent calculables par [CarbCalculator] dans le cas
     * « glucides par pièce ».
     */
    fun referenceQuantity(product: FoodProduct, mode: CalcMode, quantity: Double): Double? = when (mode) {
        CalcMode.PER_100G, CalcMode.PER_100ML -> quantity
        CalcMode.PACKAGE -> product.packageGrams?.let { it * quantity }
        CalcMode.PER_UNIT -> ServingText.gramsPerItem(product.servingLabel, product.defaultPortionGrams)
            ?.let { it * quantity }
        CalcMode.PER_PORTION -> product.defaultPortionGrams?.let { it * quantity }
            ?: product.defaultPortionMl?.let { it * quantity }
    }

    /** Le calcul de glucides de la ligne, dans les règles communes de l'application. */
    fun compute(product: FoodProduct, mode: CalcMode, quantity: Double): CarbResult {
        val unit = unitOf(mode)
        val effective = when (mode) {
            // Le paquet se ramène à son poids, en grammes.
            CalcMode.PACKAGE -> product.packageGrams?.let { it * quantity } ?: 0.0
            else -> quantity
        }
        return CarbCalculator.carbsFor(product, effective, unit)
    }

    /**
     * La formule lisible de la ligne : « 25 g × 55 g / 100 g = 13,8 g ».
     *
     * Elle est affichée sous le champ pour que l'utilisateur voie d'où sort le nombre, et pas
     * seulement combien il vaut.
     */
    fun formula(product: FoodProduct, mode: CalcMode, quantity: Double, result: CarbResult): String {
        val computed = result as? CarbResult.Computed
        val perUnit = product.carbsPerUnit
        val base = when (mode) {
            CalcMode.PER_100G -> product.carbsPer100g?.let { "${format(it)} g / 100 g" }
            CalcMode.PER_100ML -> product.carbsPer100ml?.let { "${format(it)} g / 100 ml" }
            CalcMode.PER_UNIT -> perUnit?.let { "${format(it)} g / pièce" }
                ?: product.carbsPer100g?.let { "${format(it)} g / 100 g" }
            CalcMode.PER_PORTION -> perUnit?.let { "${format(it)} g / portion" }
                ?: product.carbsPer100g?.let { "${format(it)} g / 100 g" }
            CalcMode.PACKAGE -> product.carbsPer100g?.let { "${format(it)} g / 100 g" }
        }
        val amount = when (mode) {
            CalcMode.PER_UNIT -> "${format(quantity)} pièce(s)"
            CalcMode.PER_PORTION -> {
                val size = product.defaultPortionGrams?.let { " (${format(it)} g)" }
                    ?: product.defaultPortionMl?.let { " (${format(it)} ml)" }
                    ?: ""
                "${format(quantity)} portion(s)$size"
            }
            CalcMode.PACKAGE -> {
                val weight = product.packageGrams?.let { " (${format(it * quantity)} g)" } ?: ""
                "${format(quantity)} paquet(s)$weight"
            }
            CalcMode.PER_100G -> "${format(quantity)} g"
            CalcMode.PER_100ML -> "${format(quantity)} ml"
        }
        val total = computed?.let { " = ${it.carbs.format()} g" } ?: ""
        return if (base == null) amount + total else "$amount × $base$total"
    }

    /** Vrai si la quantité est exploitable (strictement positive et finie). */
    fun isValidQuantity(quantity: Double): Boolean = quantity.isFinite() && quantity > 0.0

    /** Total d'une liste de glucides, sans jamais compter un non-chiffré pour zéro. */
    fun totalOf(values: List<Carbs?>): Carbs = Carbs.of(values.sumOf { it?.grams ?: 0.0 })

    private fun format(value: Double): String {
        val rounded = Carbs.of(value).rounded(2)
        return if (rounded == rounded.toLong().toDouble()) {
            rounded.toLong().toString()
        } else {
            String.format(java.util.Locale.ROOT, "%.2f", rounded).trimEnd('0').trimEnd('.').replace('.', ',')
        }
    }
}
