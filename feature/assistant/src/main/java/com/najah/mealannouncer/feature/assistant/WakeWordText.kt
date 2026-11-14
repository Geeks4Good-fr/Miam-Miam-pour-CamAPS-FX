package com.najah.mealannouncer.feature.assistant

import org.json.JSONException
import org.json.JSONObject

/**
 * Reconnaissance et nettoyage du mot-clé vocal « miam-miam » (`docs/MIAM-MIAM.md`).
 *
 * Fonctions pures et sans dépendance Android : elles servent à la fois au détecteur hors ligne
 * Vosk sur le téléphone, au nettoyage de la transcription Mistral quand la phrase a été dite
 * d'une traite (« Miam-miam, je mange deux pains au chocolat »), et au dialogue de la maison.
 */
object WakeWordText {

    /**
     * Mots « leurres » ajoutés à la grammaire de Vosk (essai du 09/11/2026, docs/MIAM-MIAM.md § 3).
     *
     * Réduit à « miam » et au jeton inconnu, Vosk ramenait n'importe quelle syllabe proche à
     * « miam » : 59 phrases de télévision sur 168 déclenchaient l'appli. Avec ces mots courants
     * et voisins (« Miami », « Myriam », « mais », « ma mie »…), la parole ordinaire trouve un mot
     * plus proche que « miam » : 3 sur 168, puis 0 après la réécoute par Kroko.
     */
    val DECOY_WORDS: List<String> = listOf(
        "mais", "mal", "main", "moi", "mon", "ma", "mi", "ami", "amie", "mia", "miami", "myriam",
        "mien", "bien", "viens", "rien", "maman", "mamie", "midi", "demain", "mieux", "miel", "nia",
        "lion", "il", "y", "a", "oui", "non", "le", "la", "les", "un", "une", "de", "et", "je", "on", "en",
    )

    /** Grammaire JSON fournie à Vosk : le mot-clé, les leurres et le jeton inconnu. */
    val VOSK_GRAMMAR: String =
        (listOf("miam-miam", "miam miam", "miam") + DECOY_WORDS + "[unk]")
            .joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

    private val VERIFY_TOKEN = Regex("^[a-z]?[iy]ams?$")

    /**
     * Vrai si la réécoute par le modèle de dictée ([WakeWordVerifier]) a bien entendu le mot-clé :
     * « miam » quelque part, ou deux mots consécutifs qui y ressemblent (« Diam Diam »).
     * « Miami », « Myriam » ou un « Liam » isolé ne suffisent pas.
     */
    fun verifiedByTranscript(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val tokens = CarbSanity.normalize(text).split(Regex("[^a-z]+")).filter { it.isNotBlank() }
        // « miam », mais aussi « yamiam » ou « miammiam » (Kroko colle parfois les syllabes, essai du
        // 10/11/2026) ; jamais « Miami ».
        if (tokens.any { GLUED_MIAM.matches(it) }) return true
        return tokens.zipWithNext().any { (a, b) -> VERIFY_TOKEN.matches(a) && VERIFY_TOKEN.matches(b) }
    }

    private val GLUED_MIAM = Regex("^[a-z]{0,3}miam(?:miam)?s?$")

    private val PREFIX_REGEX = Regex(
        pattern = "^[\\s,.:;!?¡¿\\-–—'\"«»]*(?:miam\\b[\\s,.:;!?¡¿\\-–—'\"«»]*)+",
        option = RegexOption.IGNORE_CASE,
    )

    /**
     * Vrai si [voskJsonOrText] contient le mot-clé « miam-miam » (ou deux « miam » consécutifs).
     *
     * Accepte aussi bien le JSON produit par Vosk (`{"partial" : "..."}` ou `{"text" : "..."}`)
     * qu'une chaîne déjà extraite. Un seul « miam » isolé ne déclenche pas : deux syllabes
     * évitent les faux départs sur un bruit bref.
     */
    fun containsWakeWord(voskJsonOrText: String?): Boolean {
        if (voskJsonOrText.isNullOrBlank()) return false
        val raw = extractVoskText(voskJsonOrText)
        if (raw.isBlank()) return false
        val normalized = CarbSanity.normalize(raw)
        val tokens = normalized.split(Regex("[^a-z]+")).filter { it.isNotBlank() }
        for (i in 0 until tokens.size - 1) {
            if (tokens[i] == "miam" && tokens[i + 1] == "miam") return true
        }
        return false
    }

    /**
     * Retire « miam-miam » (ou « miam ») en tête d'une phrase transcrite, ainsi que la
     * ponctuation qui le suit.
     *
     * Exemple : `« Miam-miam, je mange deux pains au chocolat »` → `« je mange deux pains au chocolat »`.
     */
    fun stripWakePrefix(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed.replaceFirst(PREFIX_REGEX, "").trim()
    }

    /** Extrait le champ `partial` ou `text` d'une réponse Vosk, ou rend la chaîne telle quelle. */
    internal fun extractVoskText(input: String): String {
        val trimmed = input.trim()
        if (!trimmed.startsWith("{")) return trimmed
        return try {
            val json = JSONObject(trimmed)
            when {
                json.has("partial") && !json.isNull("partial") -> json.optString("partial", "")
                json.has("text") && !json.isNull("text") -> json.optString("text", "")
                else -> ""
            }
        } catch (_: JSONException) {
            trimmed
        }
    }
}
