package com.najah.mealannouncer.core.model

import java.text.Normalizer
import java.util.Locale

/**
 * Lecture des libellés de portion d'Open Food Facts.
 *
 * Une fiche dit rarement « un biscuit = 12,5 g » : elle dit « 2 biscuits (25 g) ». Sans le
 * nombre d'articles de la portion, « deux biscuits » ne peut pas devenir un poids, et
 * l'application devrait demander des grammes à quelqu'un qui vient justement de dire
 * « deux biscuits ». Ces trois lectures — nombre d'articles, mot de l'article, poids écrit
 * dans un texte — sont donc la charnière entre ce que dit l'utilisateur et un poids.
 *
 * Tout est ici pur et sans dépendance Android : c'est éprouvable sur la JVM.
 */
object ServingText {

    /** Un poids, avec son unité : « 200 g », « 1,5 kg », « 33 cl ». */
    private val POIDS = Regex(
        "(\\d+(?:[.,]\\d+)?)\\s*(kg|kilos?|kilogrammes?|g|gr|grammes?|mg|cl|ml|l|litres?)\\b",
    )

    /**
     * Le nombre en tête d'un libellé de portion : « 2 biscuits » → 2, « Un biscuit 20 g » → 1.
     *
     * « un » et « une » comptent pour un article : c'est le même renseignement écrit en toutes
     * lettres, et c'est ainsi que les fiches françaises disent « Un biscuit 20 g » ou « Une gaufre
     * 70 g ». Sans cela, un produit dont la fiche nomme pourtant son article était refusé en mode
     * « unité(s) » (« le produit donne le poids d'une portion mais pas le nombre d'articles »).
     *
     * Une fraction (« 1/2 biscuit ») n'est **pas** un compte d'articles : on préfère ne rien lire
     * plutôt que de prendre la moitié pour un entier et de doubler les glucides.
     */
    private val NOMBRE_EN_TETE =
        Regex("^\\s*(\\d+(?:[.,]\\d+)?(?!/)|un\\b|une\\b)", RegexOption.IGNORE_CASE)

    /** Le premier mot d'un reste de libellé, lettres seules : « g, un biscuit » → « g ». */
    private val PREMIER_MOT = Regex("[a-z]+")

    /**
     * Un compte d'articles écrit **après** un poids : « 20 g, un biscuit », « 8.4g (1 biscuit) ».
     *
     * Open Food Facts écrit la même chose dans les deux sens. Quand le poids vient en premier,
     * l'article qui le suit dit à quoi ce poids correspond : un biscuit, deux biscuits. Sans
     * cette lecture, un produit aussi courant que les Prince resterait intraduisible.
     *
     * Le compte doit suivre le poids **immédiatement** (virgule ou parenthèse entre les deux) :
     * dans « 20 g, à consommer dans les 3 jours », « 3 jours » n'est pas la portion.
     */
    private val APRES_POIDS = Regex(
        "^\\s*\\d+(?:[.,]\\d+)?\\s*" +
            "(?:kg|kilos?|kilogrammes?|g|gr|grammes?|mg|cl|ml|l|litres?|oz)\\b" +
            "\\s*(?:[,;]|\\()?\\s*(\\d+(?:[.,]\\d+)?|un|une)\\s+([a-z]+)",
    )

    /** Unités qui ne désignent pas un article : un libellé qui commence par elles est un poids. */
    private val UNITES_DE_MASSE = setOf(
        "g", "gr", "gramme", "grammes", "kg", "kilo", "kilos", "kilogramme", "kilogrammes",
        "mg", "ml", "cl", "l", "litre", "litres", "oz",
    )

    /**
     * Nombre d'articles dans une portion, ou `null`.
     *
     * « 2 biscuits (25 g) » → 2 ; « 1 verre (200 ml) » → 1 ; « 25 g » → `null`, parce qu'un
     * poids n'est pas un compte d'articles. Au-delà de 100, ce n'est plus une portion mais
     * une quantité de paquet : on préfère ne rien affirmer.
     */
    fun itemsPerServing(label: String?): Double? {
        val (nombre, reste) = lireEnteteDePortion(label) ?: return null
        if (reste.isEmpty()) return null
        if (premierMot(reste) in UNITES_DE_MASSE) {
            // « 20 g, un biscuit » : le poids d'abord, le compte ensuite. C'est le même
            // renseignement que « 1 biscuit (20 g) », écrit dans l'autre sens.
            return apresPoids(label)?.first
        }
        if (nombre < 1.0 || nombre > 100.0) return null
        return nombre
    }

    /**
     * Mot de l'article d'une portion, au singulier et sans accent : « 2 biscuits (25 g) » →
     * « biscuit ». Sert à reconnaître « deux biscuits » et à refuser « deux tranches » quand
     * la fiche parle de biscuits.
     */
    fun itemWord(label: String?): String? {
        val (_, reste) = lireEnteteDePortion(label) ?: return null
        val mot = premierMot(reste)
        if (mot.isEmpty()) return null
        if (mot in UNITES_DE_MASSE) {
            return apresPoids(label)?.second?.let(::singulariser)
        }
        return singulariser(mot)
    }

    /**
     * Le compte d'articles écrit juste après un poids : « 20 g, un biscuit » → (1, « biscuit »).
     *
     * Rien n'est cherché ailleurs dans le libellé : la portion, c'est le poids **et** l'article
     * qui le commente, pas un nombre rencontré plus loin dans une phrase de conseil.
     */
    private fun apresPoids(label: String?): Pair<Double, String>? {
        val texte = label ?: return null
        if (texte.isBlank()) return null
        val trouve = APRES_POIDS.find(normaliser(texte)) ?: return null
        val mot = trouve.groupValues[2]
        if (mot in UNITES_DE_MASSE) return null
        val nombre = lireNombre(trouve.groupValues[1]) ?: return null
        if (nombre < 1.0 || nombre > 100.0) return null
        return nombre to mot
    }

    /**
     * Poids d'**un** article, quand le libellé de portion dit combien d'articles fait une portion.
     *
     * « 2 biscuits (25 g) » avec une portion de 25 g → 12,5 g par biscuit ; « 20 g, un biscuit »
     * → 20 g. C'est ce qui permet à « deux biscuits » de devenir un poids au lieu de rester une
     * unité que le calculateur refuse.
     *
     * Un libellé qui ne nomme aucun article (« 20 g », « 12,5g ») ne permet pas cette
     * conversion : on rend `null` plutôt que de supposer qu'une portion est un article.
     */
    fun gramsPerItem(servingLabel: String?, portionGrams: Double?): Double? {
        val grammes = portionGrams?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val articles = itemsPerServing(servingLabel) ?: return null
        if (articles <= 0.0) return null
        val parArticle = grammes / articles
        if (parArticle < MIN_GRAMS_PAR_ARTICLE || parArticle > MAX_GRAMS_PAR_ARTICLE) return null
        return parArticle
    }

    /**
     * Premier poids écrit dans un texte, en grammes.
     *
     * Les millilitres comptent pour des grammes, comme partout ailleurs dans l'application :
     * 100 ml de boisson pèsent 100 g à 2 % près.
     */
    fun gramsIn(text: String?): Double? {
        val trouve = POIDS.find(text?.lowercase(Locale.ROOT) ?: return null) ?: return null
        val valeur = lireNombre(trouve.groupValues[1]) ?: return null
        val grammes = when (trouve.groupValues[2]) {
            "kg", "kilo", "kilos", "kilogramme", "kilogrammes" -> valeur * 1_000.0
            "mg" -> valeur / 1_000.0
            "cl" -> valeur * 10.0
            "l", "litre", "litres" -> valeur * 1_000.0
            else -> valeur
        }
        if (grammes < 0.1 || grammes > 30_000.0) return null
        return grammes
    }

    /** La partie du libellé qui décrit les articles, avant le poids entre parenthèses. */
    private fun enteteDePortion(label: String?): String? {
        val texte = label?.trim().orEmpty()
        if (texte.isEmpty()) return null
        return texte.substringBefore('(').trim().ifEmpty { null }
    }

    /** Nombre en tête et ce qui le suit : « 2 biscuits » → (2, « biscuits »). */
    private fun lireEnteteDePortion(label: String?): Pair<Double, String>? {
        val tete = enteteDePortion(label) ?: return null
        val trouve = NOMBRE_EN_TETE.find(tete) ?: return null
        val nombre = lireNombre(trouve.groupValues[1]) ?: return null
        val reste = tete.substring(trouve.range.last + 1).trim()
        return nombre to reste
    }

    /** « 2 » ou « 2,5 », et « un »/« une » dans un compte d'articles ; `null` sinon. */
    private fun lireNombre(texte: String): Double? {
        val mot = texte.trim().lowercase(Locale.ROOT).replace(',', '.')
        if (mot == "un" || mot == "une") return 1.0
        val valeur = mot.toDoubleOrNull() ?: return null
        return if (valeur.isFinite()) valeur else null
    }

    /**
     * Premier mot du libellé, en lettres seules.
     *
     * La ponctuation compte : le libellé « 20 g, un biscuit » commence par un poids suivi
     * d'une virgule, et s'arrêter à l'espace donnerait « g, », qui ne serait pas reconnu
     * comme une unité de masse — la fiche passerait alors pour une portion de 20 articles.
     */
    private fun premierMot(texte: String): String =
        PREMIER_MOT.find(normaliser(texte))?.value.orEmpty()

    private fun normaliser(texte: String): String {
        val sansAccent = Normalizer.normalize(texte, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return sansAccent.lowercase(Locale.ROOT).trim()
    }

    private fun singulariser(mot: String): String =
        if (mot.length > 3 && mot.endsWith("s")) mot.dropLast(1) else mot

    /** Bornes d'un poids d'article : en deçà et au-delà, le libellé n'est pas crédible. */
    private const val MIN_GRAMS_PAR_ARTICLE = 0.5

    private const val MAX_GRAMS_PAR_ARTICLE = 2_000.0
}
