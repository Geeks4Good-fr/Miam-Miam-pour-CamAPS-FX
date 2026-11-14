package com.najah.mealannouncer.feature.voice

import kotlin.math.roundToInt

/**
 * Fabrique la phrase lue à voix haute pour un repas.
 *
 * Pure Kotlin, sans Android : c'est le texte, pas la voix. Le même texte sert au moteur
 * Voxtral et à la synthèse Android, et au cache (la clé de cache est l'empreinte de ce texte).
 *
 * Règles :
 * - le texte ne contient **que** les glucides du repas — jamais de glycémie ni d'insuline ;
 * - les glucides sont arrondis au gramme : une annonce parlée n'a pas besoin de décimales,
 *   et la valeur exacte reste à l'écran de confirmation ;
 * - le pluriel est correct (« 1 gramme », « 62 grammes ») et zéro se dit en toutes lettres.
 */
object MealAnnouncementText {

    const val DEFAULT_PREFIX = "Repas annoncé"

    /**
     * Phrase pour [carbsGrams] grammes de glucides, par exemple
     * « Repas annoncé : 62 grammes de glucides. ».
     *
     * [prefix] permet d'annoncer avant ou après l'envoi sans changer le reste.
     */
    fun forCarbs(carbsGrams: Double, prefix: String = DEFAULT_PREFIX): String {
        val safePrefix = prefix.trim().ifBlank { DEFAULT_PREFIX }
        val grams = if (carbsGrams.isFinite()) carbsGrams.roundToInt().coerceAtLeast(0) else 0
        val quantity = when (grams) {
            0 -> "aucun gramme"
            1 -> "1 gramme"
            else -> "$grams grammes"
        }
        return "$safePrefix : $quantity de glucides."
    }
}
