package com.najah.mealannouncer.feature.assistant

import java.text.Normalizer
import java.util.Locale

/**
 * Reconnaît si un produit d'Open Food Facts correspond vraiment à l'aliment demandé.
 *
 * La recherche d'Open Food Facts est floue : interrogée sur « banane », elle a rendu un
 * fromage blanc. Prendre le premier résultat revenait donc à chiffrer un aliment avec les
 * glucides d'un autre — la faute la plus grave que cette application puisse commettre. On
 * exige ici que le nom du produit reprenne réellement les mots de l'aliment demandé ; à
 * défaut, l'aliment part sur Internet, ou reste affiché comme non chiffré.
 */
internal object FoodNameMatcher {

    /** Mots de liaison : ils ne disent rien de l'aliment lui-même. */
    private val STOP_WORDS = setOf(
        "de", "du", "des", "la", "le", "les", "au", "aux", "et", "avec", "sans",
        "the", "of", "and", "with",
    )

    private val ACCENTS = Regex("\\p{Mn}+")
    private val PUNCTUATION = Regex("[^a-z0-9]+")

    /** Ramène un nom à ses mots simples : minuscules, sans accent, sans ponctuation. */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        return decomposed
            .replace(ACCENTS, "")
            .lowercase(Locale.ROOT)
            .replace(PUNCTUATION, " ")
            .trim()
    }

    /**
     * Vrai si [productName] peut désigner [query].
     *
     * Deux cas suffisent en pratique : le produit contient l'aliment (« banane » dans
     * « banane plantain »), ou les mots significatifs se retrouvent d'un côté comme de l'autre
     * (« riz basmati cuit » et « riz basmati » partagent deux mots sur trois).
     *
     * Le cas inverse — un nom de produit **plus court** que la demande — n'est volontairement
     * pas accepté d'office : « tajine » ne doit pas servir à chiffrer « tajine de poulet aux
     * olives », ni « riz » à chiffrer « salade de riz ». Un plat préparé de marque n'a pas les
     * glucides d'une recette maison, et se tromper d'un facteur trois est ici la faute la plus
     * grave. Le compte de mots s'en charge : il faut au moins deux mots retrouvés sur trois.
     */
    fun isPlausible(query: String, productName: String): Boolean {
        val wanted = normalize(query)
        val offered = normalize(productName)
        if (wanted.isEmpty() || offered.isEmpty()) return false
        if (offered.contains(wanted)) return true

        val queryWords = words(wanted)
        if (queryWords.isEmpty()) return false
        val offeredWords = words(offered).toSet()
        val matched = queryWords.count { it in offeredWords }
        // Un aliment d'un seul mot doit être retrouvé en entier : « banane » ne doit pas
        // s'accepter d'un produit qui ne partagerait qu'un mot vague.
        val required = if (queryWords.size <= 2) {
            queryWords.size
        } else {
            maxOf(2, (queryWords.size + 1) / 2)
        }
        return matched >= required
    }

    private fun words(normalized: String): List<String> =
        normalized.split(' ')
            .filter { it.length >= MIN_WORD_LENGTH && it !in STOP_WORDS }
            .distinct()

    /** En deçà, un mot ne distingue plus rien (« de », « au », « 20 »). */
    private const val MIN_WORD_LENGTH = 3
}
