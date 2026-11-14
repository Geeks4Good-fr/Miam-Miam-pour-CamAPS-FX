package com.najah.mealannouncer.core.model

/** Nature d'une unité de quantité, qui détermine la conversion possible. */
enum class MealUnitKind {
    /** Poids : grammes. */
    MASS,

    /** Volume : millilitres. */
    VOLUME,

    /** Décompte : unités, portions, tranches. */
    COUNT,

    /** Unité non reconnue par le parseur. */
    UNKNOWN,
}

/**
 * Unité dans laquelle l'utilisateur a exprimé une quantité.
 *
 * L'ordre et les noms suivent le cahier des charges : c'est ce que produit le
 * parseur de phrases françaises (« deux biscuits », « 200 ml », « une tranche »).
 */
enum class MealUnit(val kind: MealUnitKind) {
    /** Décompte simple : « deux biscuits », « un yaourt ». */
    UNIT(MealUnitKind.COUNT),

    /** Poids : « 100 grammes ». */
    GRAM(MealUnitKind.MASS),

    /** Volume : « 200 ml ». */
    MILLILITER(MealUnitKind.VOLUME),

    /** Portion générique : « une portion ». */
    PORTION(MealUnitKind.COUNT),

    /** Tasse : « une tasse de Candy Up » (volume conventionnel, voir [UnitConversions]). */
    CUP(MealUnitKind.VOLUME),

    /** Tranche : « une tranche de pain ». */
    SLICE(MealUnitKind.COUNT),

    /** Le parseur n'a pas su déterminer l'unité. */
    UNKNOWN(MealUnitKind.UNKNOWN),
}
