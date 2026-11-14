package com.najah.mealannouncer.feature.assistant

import com.najah.mealannouncer.core.model.ServingText
import java.text.Normalizer
import java.util.Locale

/**
 * Ce qu'on sait de l'aliment au moment où la portion est demandée.
 *
 * Ces trois données viennent de la fiche Open Food Facts : le poids d'une portion, son
 * libellé (« 2 biscuits (25 g) ») et le poids du paquet. Sans elles, « deux biscuits » ou
 * « un tiers » ne se convertissent pas — et c'est alors une question qu'il faut poser, pas
 * un poids qu'il faut inventer.
 */
data class PortionContext(
    val name: String,
    val servingGrams: Double? = null,
    val servingLabel: String? = null,
    val packageGrams: Double? = null,
) {
    /** Nombre d'articles d'une portion, quand le libellé le dit. */
    val itemsPerServing: Double? get() = ServingText.itemsPerServing(servingLabel)

    /** Mot de l'article d'une portion, au singulier : « biscuit ». */
    val itemWord: String? get() = ServingText.itemWord(servingLabel)
}

/**
 * Ce qu'une réponse de l'utilisateur a donné.
 *
 * Trois issues, et aucune quatrième : un poids calculable, une question à poser parce
 * qu'il manque une base, ou l'aveu qu'on n'a pas compris. Un poids « probable » n'existe
 * pas ici : c'est précisément ce qui finirait dans un calcul d'insuline.
 */
sealed interface PortionReading {

    /** La réponse a donné un poids, avec le calcul qui y mène, affichable tel quel. */
    data class Grams(
        val grams: Double,
        val understood: String,
        val detail: String,
    ) : PortionReading

    /** Il manque une base (« un tiers de quoi ? ») : on demande, on ne suppose pas. */
    data class Ask(val understood: String, val question: String) : PortionReading

    /** Rien d'exploitable : ni poids, ni fraction, ni compte d'articles. */
    data class Unclear(val understood: String) : PortionReading
}

/**
 * Lit une réponse de portion en français : « 150 g », « deux biscuits », « un tiers du
 * paquet », « 50 % », « une portion ».
 *
 * Pourquoi un analyseur écrit à la main plutôt qu'un modèle pour tout ? Parce que les
 * réponses courantes sont simples, qu'elles doivent marcher sans réseau, et qu'un calcul
 * vérifiable (« 2 biscuits × 12,5 g = 25 g ») vaut mieux qu'une réponse de modèle qu'on ne
 * peut que croire. Le modèle garde son rôle : ce que cet analyseur ne comprend pas lui est
 * confié ensuite (voir `PortionInterpreter`).
 *
 * Bornes : un poids hors de 1–3000 g n'est pas une portion mais une erreur de lecture, et
 * il est refusé comme tel.
 */
object PortionParser {

    private const val MIN_GRAMS = 1.0
    private const val MAX_GRAMS = 3_000.0

    private val POIDS = Regex(
        "(\\d+(?:[.,]\\d+)?)\\s*(kg|kilos?|kilogrammes?|g|gr|grammes?|mg)\\b",
    )

    private val POURCENT = Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:%|pour cent)")

    /** Les nombres dits en lettres, jusqu'à ce qu'on puisse raisonnablement compter. */
    private val NOMBRES = mapOf(
        "un" to 1.0, "une" to 1.0, "deux" to 2.0, "trois" to 3.0, "quatre" to 4.0,
        "cinq" to 5.0, "six" to 6.0, "sept" to 7.0, "huit" to 8.0, "neuf" to 9.0,
        "dix" to 10.0, "onze" to 11.0, "douze" to 12.0, "treize" to 13.0,
        "quatorze" to 14.0, "quinze" to 15.0, "seize" to 16.0, "vingt" to 20.0,
        "trente" to 30.0,
    )

    private const val NOMBRE = "(\\d+(?:[.,]\\d+)?|un|une|deux|trois|quatre|cinq|six|sept|" +
        "huit|neuf|dix|onze|douze|treize|quatorze|quinze|seize|vingt|trente)"

    private val FRACTION = Regex("$NOMBRE?\\s*(demi|demie|moitie|quart|quarts|tiers|tier)\\b")

    private const val ARTICLES = "tranches?|biscuits?|parts?|morceaux?|portions?|doses?|" +
        "cuilleres?|cuillerees?|verres?|bols?|assiettes?|unites?|sachets?|paquets?|" +
        "briques?|tablettes?|boules?|tasses?"

    private val ITEMS = Regex("$NOMBRE\\s+(?:de\\s+)?($ARTICLES)\\b")

    private val NOMBRE_SEUL = Regex("^\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:g|gr|grammes?)?\\s*$")

    /** Mots qui désignent un contenant entier : « un tiers du paquet ». */
    private val PAQUETS = setOf(
        "paquet", "paquets", "sachet", "sachets", "boite", "boites", "brique", "briques",
        "tablette", "tablettes", "pack", "packs", "bouteille", "bouteilles",
    )

    /** Les mêmes, au singulier, pour comparer un article compté (« deux paquets »). */
    private val PAQUETS_SINGULIERS =
        setOf("paquet", "sachet", "boite", "brique", "tablette", "pack", "bouteille")

    /** Mots qui désignent une portion servie : « la moitié de la portion ». */
    private val PORTIONS = setOf("portion", "portions", "part", "parts", "dose", "doses")

    private val PORTIONS_SINGULIERES = setOf("portion", "part", "dose")

    /**
     * Lit [text] pour l'aliment décrit par [context].
     *
     * L'ordre des essais n'est pas interchangeable : un pourcentage et une fraction passent
     * avant les poids, parce que « un demi paquet de 200 g » contient un poids qui est celui
     * du paquet, pas celui de ce qui a été mangé.
     */
    fun read(text: String, context: PortionContext): PortionReading {
        val observe = text.trim()
        val phrase = normaliser(observe)
        if (phrase.isEmpty()) return PortionReading.Unclear(observe)

        lirePourcentage(phrase, observe, context)?.let { return it }
        lireFraction(phrase, observe, context)?.let { return it }
        lireArticles(phrase, observe, context)?.let { return it }
        lirePoids(phrase, observe)?.let { return it }

        NOMBRE_SEUL.find(phrase)?.groupValues?.get(1)?.let { nombre ->
            val grams = lireNombre(nombre)
            if (grams != null && dansLesBornes(grams)) {
                return PortionReading.Grams(
                    grams = grams,
                    understood = observe,
                    detail = "${formatQuantityText(grams)} g",
                )
            }
        }
        return PortionReading.Unclear(observe)
    }

    /** « 50 % » : un pourcentage de la portion connue, sinon une question. */
    private fun lirePourcentage(
        phrase: String,
        observe: String,
        context: PortionContext,
    ): PortionReading? {
        val trouve = POURCENT.find(phrase) ?: return null
        val pourcent = lireNombre(trouve.groupValues[1]) ?: return null
        if (pourcent <= 0.0 || pourcent > 500.0) return null
        val portion = context.servingGrams
            ?: return PortionReading.Ask(
                observe,
                "Ces ${formatQuantityText(pourcent)} %, c'est combien de grammes ?",
            )
        val grams = portion * pourcent / 100.0
        if (!dansLesBornes(grams)) return null
        return PortionReading.Grams(
            grams = grams,
            understood = observe,
            detail = "${formatQuantityText(pourcent)} % de la portion de " +
                "${formatQuantityText(portion)} g = ${formatQuantityText(grams)} g",
        )
    }

    /**
     * « un tiers », « la moitié », « trois quarts ».
     *
     * Une fraction sans base explicite est **toujours** une question : « un tiers de quoi ? ».
     * C'est la règle que l'utilisateur a fixée, et elle est aussi la plus sûre — un tiers du paquet et
     * un tiers de l'assiette ne font pas le même nombre de grammes.
     */
    private fun lireFraction(
        phrase: String,
        observe: String,
        context: PortionContext,
    ): PortionReading? {
        val trouve = FRACTION.find(phrase) ?: return null
        val mot = trouve.groupValues[2]
        val nombre = trouve.groupValues[1].takeIf { it.isNotBlank() }?.let(::lireNombre) ?: 1.0
        val ratio = nombre * ratioDe(mot)
        if (ratio <= 0.0 || ratio >= 1.0) return null
        val phraseAffichee = phraseFraction(nombre, mot)

        val dansUnPaquet = contientUn(phrase, PAQUETS)
        val dansUnePortion = contientUn(phrase, PORTIONS)
        // Un poids écrit à côté d'une fraction (« un demi sachet de 200 g ») est celui du
        // contenant : il sert de base quand la fiche ne la donne pas.
        val poidsEcrit = ServingText.gramsIn(phrase)

        val base = when {
            dansUnPaquet -> context.packageGrams ?: poidsEcrit
            dansUnePortion -> context.servingGrams ?: poidsEcrit
            else -> null
        }
        if (base == null) {
            val question = if (dansUnPaquet || dansUnePortion) {
                val contenant = if (dansUnPaquet) "Ce paquet pèse" else "Cette portion pèse"
                "$phraseAffichee : $contenant combien de grammes ?"
            } else {
                "$phraseAffichee de quoi ? Dites « du paquet » ou « de la portion », " +
                    "ou donnez un poids en grammes."
            }
            return PortionReading.Ask(observe, question)
        }

        val grams = base * ratio
        if (!dansLesBornes(grams)) return null
        val contenant = when {
            dansUnPaquet -> "du paquet de ${formatQuantityText(base)} g"
            dansUnePortion -> "de la portion de ${formatQuantityText(base)} g"
            else -> "de ${formatQuantityText(base)} g"
        }
        return PortionReading.Grams(
            grams = grams,
            understood = observe,
            detail = "$phraseAffichee $contenant = ${formatQuantityText(grams)} g",
        )
    }

    /**
     * « deux biscuits », « une portion », « trois tranches ».
     *
     * Le compte d'articles ne se convertit que si la fiche dit combien d'articles fait une
     * portion (« 2 biscuits (25 g) »). Un biscuit de 25 g et une portion de deux biscuits ne
     * donnent pas le même poids : dans le doute, on demande des grammes.
     */
    private fun lireArticles(
        phrase: String,
        observe: String,
        context: PortionContext,
    ): PortionReading? {
        val trouve = ITEMS.find(phrase) ?: return null
        val nombre = lireNombre(trouve.groupValues[1]) ?: return null
        if (nombre <= 0.0 || nombre > 100.0) return null
        val article = trouve.groupValues[2]
        val articleSingulier = singulariser(article)
        val dit = trouverDit(trouve.value)

        // « une portion », « deux parts » : la portion de la fiche, comptée.
        if (articleSingulier in PORTIONS_SINGULIERES) {
            val portion = context.servingGrams ?: return PortionReading.Ask(
                observe,
                "$dit, ça fait combien de grammes ?",
            )
            val grams = portion * nombre
            if (!dansLesBornes(grams)) return null
            return PortionReading.Grams(
                grams = grams,
                understood = observe,
                detail = "$dit × ${formatQuantityText(portion)} g = " +
                    "${formatQuantityText(grams)} g",
            )
        }

        // « deux paquets », « un sachet » : le contenant entier.
        if (articleSingulier in PAQUETS_SINGULIERS) {
            val paquet = context.packageGrams ?: return PortionReading.Ask(
                observe,
                "$dit, ça fait combien de grammes ?",
            )
            val grams = paquet * nombre
            if (!dansLesBornes(grams)) return null
            return PortionReading.Grams(
                grams = grams,
                understood = observe,
                detail = "$dit × ${formatQuantityText(paquet)} g = " +
                    "${formatQuantityText(grams)} g",
            )
        }

        // « deux biscuits » : convertible seulement si la fiche dit le poids d'un biscuit.
        val motDeLaFiche = context.itemWord
        val articlesParPortion = context.itemsPerServing
        val portion = context.servingGrams
        if (motDeLaFiche != null && articlesParPortion != null && portion != null &&
            memesMots(articleSingulier, motDeLaFiche)
        ) {
            val parArticle = portion / articlesParPortion
            val grams = parArticle * nombre
            if (!dansLesBornes(grams)) return null
            return PortionReading.Grams(
                grams = grams,
                understood = observe,
                detail = "$dit × ${formatQuantityText(parArticle)} g = " +
                    "${formatQuantityText(grams)} g",
            )
        }

        return PortionReading.Ask(observe, "$dit, ça fait combien de grammes ?")
    }

    /** « 150 g », « 1,5 kg » : la réponse la plus simple, et celle qui ne trompe pas. */
    private fun lirePoids(phrase: String, observe: String): PortionReading? {
        val trouve = POIDS.find(phrase) ?: return null
        val valeur = lireNombre(trouve.groupValues[1]) ?: return null
        val grams = when (trouve.groupValues[2]) {
            "kg", "kilo", "kilos", "kilogramme", "kilogrammes" -> valeur * 1_000.0
            "mg" -> valeur / 1_000.0
            else -> valeur
        }
        if (!dansLesBornes(grams)) return null
        return PortionReading.Grams(
            grams = grams,
            understood = observe,
            detail = "${formatQuantityText(grams)} g",
        )
    }

    /** Part d'un tout que représente une fraction dite : « tiers » → 1/3. */
    private fun ratioDe(mot: String): Double = when (mot) {
        "demi", "demie", "moitie" -> 0.5
        "quart", "quarts" -> 0.25
        else -> 1.0 / 3.0
    }

    /**
     * La fraction telle qu'on la redit à l'oral : « La moitié », « Le tiers », « Les trois
     * quarts ». Sans cela, la question posée reprendrait « moitie », sans accent ni article.
     */
    internal fun phraseFraction(nombre: Double, mot: String): String {
        val unite = when (mot) {
            "demi", "demie", "moitie" -> "moitié"
            "quart", "quarts" -> "quart"
            else -> "tiers"
        }
        return when {
            nombre == 1.0 && unite == "moitié" -> "La moitié"
            nombre == 1.0 -> "Le $unite"
            unite == "quart" -> "Les ${motDeNombre(nombre)} quarts"
            unite == "moitié" -> "Les ${motDeNombre(nombre)} demis"
            else -> "Les ${motDeNombre(nombre)} tiers"
        }
    }

    /** « 3 » → « trois », pour redire une fraction à l'oral. */
    private fun motDeNombre(nombre: Double): String {
        if (nombre == nombre.toLong().toDouble()) {
            NOMBRES.entries.firstOrNull { it.value == nombre && it.key != "une" }?.let {
                return it.key
            }
            return nombre.toLong().toString()
        }
        return formatQuantityText(nombre)
    }

    /** Vrai si [phrase] contient un des [mots], en mot entier. */
    private fun contientUn(phrase: String, mots: Set<String>): Boolean =
        phrase.split(' ', ',', ';', '.', '\'').any { it in mots }

    private fun memesMots(a: String, b: String): Boolean {
        val x = singulariser(a)
        val y = singulariser(b)
        return x == y || x.startsWith(y) || y.startsWith(x)
    }

    private fun dansLesBornes(grams: Double): Boolean =
        grams.isFinite() && grams >= MIN_GRAMS && grams <= MAX_GRAMS

    private fun lireNombre(texte: String): Double? {
        val mot = texte.trim()
        if (mot.isEmpty()) return null
        NOMBRES[mot]?.let { return it }
        val valeur = mot.replace(',', '.').toDoubleOrNull() ?: return null
        return if (valeur.isFinite()) valeur else null
    }

    /** Minuscules, sans accent, sans ponctuation lourde : la même forme que la recherche. */
    internal fun normaliser(texte: String): String {
        val sansAccent = Normalizer.normalize(texte, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return sansAccent
            .lowercase(Locale.ROOT)
            .replace('’', '\'')
            .replace(Regex("[^a-z0-9%,' ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun singulariser(mot: String): String =
        if (mot.length > 3 && mot.endsWith("s")) mot.dropLast(1) else mot

    /** Le texte réellement entendu, pour le citer dans une question (« Deux biscuits, … »). */
    private fun trouverDit(matched: String): String =
        matched.replaceFirstChar { it.titlecase(Locale.FRENCH) }
}
