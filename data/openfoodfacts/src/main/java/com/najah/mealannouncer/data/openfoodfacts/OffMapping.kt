package com.najah.mealannouncer.data.openfoodfacts

import java.util.Locale

/**
 * Normalisations appliquées aux valeurs d'Open Food Facts avant d'en faire des glucides.
 *
 * Ces fonctions sont pures et sans dépendance Android : elles sont testables sur la JVM,
 * alors que la lecture du JSON — qui utilise `org.json` — ne l'est pas. Toute la logique
 * qui décide « cette valeur est-elle utilisable » vit donc ici.
 *
 * La base de données collaborative contient de tout : des valeurs à 0 pour un produit qui
 * n'a simplement pas été renseigné, des glucides à 250 g pour 100 g (impossible), des
 * portions négatives. Une application qui recopie ces valeurs sans filtre annonce
 * n'importe quoi.
 */
object OffMapping {

    /** Au-delà de 100 g de glucides pour 100 g, la valeur est nécessairement fausse. */
    private const val MAX_CARBS_PER_100 = 100.0

    /**
     * Une valeur « pour 100 g/ml » exploitable, ou `null`.
     *
     * Zéro est conservé : l'eau contient réellement 0 glucide, et ce n'est pas la même
     * chose qu'une donnée absente.
     */
    fun validCarbsPer100(value: Double?): Double? {
        if (value == null) return null
        if (value.isNaN() || value.isInfinite()) return null
        if (value < 0.0) return null
        if (value > MAX_CARBS_PER_100) return null
        return value
    }

    /**
     * Glucides d'une portion, déduits de la valeur pour 100 g/ml et de la taille de la
     * portion. Sert à donner un sens à « 1 unité » pour un biscuit dont l'étiquette
     * indique « 1 biscuit = 16,5 g ».
     */
    fun carbsForServing(carbsPer100: Double?, servingQuantity: Double?): Double? {
        val carbs = validCarbsPer100(carbsPer100) ?: return null
        val serving = validServingQuantity(servingQuantity) ?: return null
        return carbs * serving / 100.0
    }

    /** Taille de portion plausible : positive, finie, et pas démesurée. */
    fun validServingQuantity(value: Double?): Double? {
        if (value == null) return null
        if (value.isNaN() || value.isInfinite()) return null
        if (value <= 0.0) return null
        if (value > 10_000.0) return null
        return value
    }

    /**
     * Vrai si l'unité de portion désigne un volume, faux si elle désigne une masse.
     *
     * Open Food Facts n'est pas constant : « ml », « cl », « L » y apparaissent tous.
     */
    fun servingIsVolume(unit: String?): Boolean {
        val normalized = unit?.trim()?.lowercase(Locale.ROOT) ?: return false
        return normalized == "ml" || normalized == "cl" || normalized == "l" ||
            normalized == "dl" || normalized == "fl oz"
    }

    /** Convertit une quantité exprimée dans l'unité de portion en grammes ou millilitres. */
    fun servingToBaseQuantity(quantity: Double?, unit: String?): Double? {
        val value = validServingQuantity(quantity) ?: return null
        return when (unit?.trim()?.lowercase(Locale.ROOT)) {
            null, "", "g", "gramme", "grammes", "gr", "ml", "millilitre", "millilitres" -> value
            "kg" -> value * 1000.0
            "cl" -> value * 10.0
            "dl" -> value * 100.0
            "l", "litre", "litres" -> value * 1000.0
            else -> null
        }
    }

    /**
     * Nettoie un nom de produit. Beaucoup de fiches sont vides ou remplies d'espaces ;
     * un produit sans nom n'a aucune utilité et doit être écarté.
     */
    fun cleanName(raw: String?): String? {
        val text = raw?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (text.isEmpty()) return null
        if (text.length > 200) return text.take(200).trim()
        return text
    }

    /** Garde la première marque : Open Food Facts les sépare par des virgules. */
    fun cleanBrand(raw: String?): String? {
        val text = raw?.substringBefore(',')?.trim() ?: return null
        return if (text.isEmpty()) null else text
    }
}
