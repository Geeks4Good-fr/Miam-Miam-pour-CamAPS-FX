package com.najah.mealannouncer.data.openfoodfacts

import java.text.Normalizer
import java.util.Locale

/**
 * Normalisation du texte pour la base Open Food Facts hors ligne.
 *
 * La base du téléphone est indexée sur des noms ramenés à des mots simples (minuscules, sans
 * accent, sans ponctuation) par le script qui la construit. La requête doit subir exactement
 * la même transformation, sinon une recherche accentuée ne trouverait jamais rien.
 */
object OffText {

    private val STOP_WORDS = setOf(
        // Mots de liaison.
        "de", "du", "des", "la", "le", "les", "au", "aux", "et", "avec", "sans",
        "un", "une", "mon", "ma", "mes", "ton", "ta", "tes", "ce", "cet", "cette", "ces",
        // Mesures et contenants : ils disent la portion, jamais l'aliment. Les garder faisait
        // chercher « grammes » et rendait des purées Picard au lieu du riz demandé.
        "g", "gr", "gramme", "grammes", "kg", "kilo", "kilos", "kilogramme", "kilogrammes",
        "ml", "cl", "litre", "litres", "centilitre", "centilitres", "millilitre", "millilitres",
        "cuillere", "cuilleres", "culliere", "cas", "cac", "verre", "verres", "bol", "bols",
        "assiette", "assiettes", "portion", "portions", "part", "parts",
        "tranche", "tranches", "morceau", "morceaux", "piece", "pieces",
        "sachet", "sachets", "boite", "boites", "pot", "pots", "paquet", "paquets",
        "unite", "unites", "environ",
    )

    private val ACCENTS = Regex("\\p{Mn}+")
    private val PUNCTUATION = Regex("[^a-z0-9]+")

    /** Minuscules, sans accent, sans ponctuation, espaces simples. */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        return decomposed
            .replace(ACCENTS, "")
            .lowercase(Locale.ROOT)
            .replace(PUNCTUATION, " ")
            .trim()
    }

    /**
     * Mots interrogeables d'une requête.
     *
     * Les mots d'un seul caractère, les mots de liaison et les nombres sont écartés : un
     * « 200 » de « 200 g de riz » n'identifie aucun produit et ferait échouer la recherche
     * en exigeant qu'il figure dans le nom.
     */
    fun tokens(query: String): List<String> =
        normalize(query)
            .split(' ')
            .filter { it.length >= MIN_WORD_LENGTH && it !in STOP_WORDS && !it.isNumber() }
            .distinct()

    private fun String.isNumber(): Boolean = all { it.isDigit() }

    /**
     * Requêtes d'index à essayer, de la plus stricte à la plus large.
     *
     * La base n'est pas tenue à jour : « riz basmati cuit » peut n'y exister que sous
     * « Riz basmati ». Plutôt que de rendre zéro et d'envoyer l'aliment sur Internet, on
     * retire un mot à la fois en commençant par la fin — le dernier mot est presque toujours
     * le qualificatif (« cuit », « frais », « bio »), tandis que le premier nomme l'aliment —
     * puis on se rabat sur le mot le plus long, qui l'identifie à lui seul.
     *
     * La liste est courte à dessein : chaque essai coûte une lecture d'index.
     */
    fun matchAttempts(query: String): List<String> {
        val words = tokens(query)
        if (words.isEmpty()) return emptyList()
        val essais = mutableListOf(words.joinToString(" ") { "$it*" })
        if (words.size > 1) {
            for (indice in words.indices.reversed()) {
                val reste = words.filterIndexed { position, _ -> position != indice }
                if (reste.isEmpty()) continue
                essais += reste.joinToString(" ") { "$it*" }
                if (essais.size >= MAX_ATTEMPTS - 1) break
            }
            essais += "${words.maxBy { it.length }}*"
        }
        return essais.distinct().take(MAX_ATTEMPTS)
    }

    private const val MIN_WORD_LENGTH = 2

    /** Au-delà, la recherche ferait plus de lectures d'index qu'elle ne gagne de résultats. */
    private const val MAX_ATTEMPTS = 5
}
