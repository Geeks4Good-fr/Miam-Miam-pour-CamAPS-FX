package com.najah.mealannouncer.core.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Quantité de glucides, toujours exprimée en grammes.
 *
 * Une valeur négative ou non finie n'a pas de sens : passer par [of] pour la créer
 * avec validation. Le constructeur direct sert aux calculs internes déjà validés.
 */
@JvmInline
value class Carbs(val grams: Double) {

    /** Additionne deux quantités de glucides. */
    operator fun plus(other: Carbs): Carbs = Carbs(grams + other.grams)

    /**
     * Arrondit au dixième de gramme, comme l'affichent les étiquettes.
     *
     * Passe par [BigDecimal] : en virgule flottante, 27,45 vaut 27,449999… et un arrondi
     * naïf donnerait 27,4 au lieu de 27,5. Un arrondi qui dépend de la représentation
     * binaire n'a pas sa place dans un chiffre que l'utilisateur recopie.
     */
    fun rounded(decimals: Int = 1): Double {
        require(decimals >= 0) { "Nombre de décimales négatif : $decimals" }
        return BigDecimal.valueOf(grams)
            .setScale(decimals, RoundingMode.HALF_UP)
            .toDouble()
    }

    /**
     * Arrondit au dixième de gramme et renvoie un texte français :
     * « 22 » pour 22,0 et « 22,5 » pour 22,5 (jamais « 22,0 »).
     */
    fun format(decimals: Int = 1): String {
        val value = rounded(decimals)
        var text = String.format(java.util.Locale.ROOT, "%.${decimals}f", value)
        if (text.contains('.')) {
            // Locale indépendante : on retire les zéros inutiles, puis on virgulise.
            text = text.trimEnd('0').trimEnd('.')
        }
        return text.replace('.', ',')
    }

    /**
     * Valeur entière à saisir dans CamAPS FX, qui n'accepte que des grammes entiers.
     * Un demi-gramme est arrondi au supérieur : mieux vaut annoncer un gramme de trop
     * que de sous-déclarer un repas.
     */
    fun toCamapsGrams(): Int = rounded(0).toInt()

    override fun toString(): String = "${format()} g"

    companion object {
        val ZERO = Carbs(0.0)

        /** Crée une quantité de glucides en refusant les valeurs absurdes. */
        fun of(grams: Double): Carbs {
            require(grams.isFinite()) { "Glucides non finis : $grams" }
            require(grams >= 0.0) { "Glucides négatifs : $grams" }
            return Carbs(grams)
        }
    }
}
