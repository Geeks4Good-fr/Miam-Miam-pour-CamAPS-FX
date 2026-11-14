package com.najah.mealannouncer.feature.assistant

import com.najah.mealannouncer.data.homeassistant.ExpectedAnswer
import com.najah.mealannouncer.data.homeassistant.HomeVoiceLink
import kotlin.coroutines.cancellation.CancellationException

/**
 * Le canal maison d'un repas : la bouche et l'oreille de **l'enceinte qui a entendu la phrase**
 * (`docs/VOIX-MAISON.md` § 3.3 et § 5).
 *
 * Le dialogue reste celui du téléphone ; seul l'endroit où l'on parle et où l'on écoute change.
 * Tout échec du lien (Wi-Fi, Tailscale, HA qui redémarre, enceinte occupée) est rendu comme
 * [Heard.LinkLost] ou `false` : c'est au dialogue de basculer sur le téléphone et de le dire.
 * Une annulation de coroutine, elle, n'est jamais avalée.
 */
class HomeVoiceChannel(
    private val link: HomeVoiceLink,
    val satellite: String,
) {

    /** Ce que l'enceinte a rapporté après une question. */
    sealed interface Heard {
        data class Text(val text: String) : Heard
        data object Silence : Heard
        data object LinkLost : Heard
    }

    /** Dernier échec du lien, gardé pour le journal : le dialogue, lui, ne voit que vrai/faux. */
    @Volatile
    var lastFailure: Exception? = null
        private set

    /** Fait dire [text] par l'enceinte. Faux si le lien est perdu : rien n'a été entendu. */
    suspend fun say(text: String): Boolean = try {
        if (speakable(text)) link.say(satellite, text)
        awaitQuiet()
        true
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: Exception) {
        lastFailure = failure
        false
    }

    /**
     * La Voice PE rend la main avant d'avoir fini de parler : on attend que son haut-parleur se
     * taise. Un échec ici ne dit rien du lien (la phrase est partie) : on continue simplement.
     */
    private suspend fun awaitQuiet() {
        try {
            link.awaitQuiet(satellite)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
        }
    }

    /**
     * Pose [question] par l'enceinte, qui rouvre son micro d'elle-même.
     *
     * Quand Home Assistant reconnaît l'une des [answers], on rend la **première phrase** de
     * cette réponse : c'est une forme que les lecteurs du dialogue (type de repas, confirmation)
     * comprennent à coup sûr. Sinon, on rend la phrase telle qu'elle a été entendue — une
     * contestation (« non, ce n'est pas ce produit ») doit arriver intacte.
     */
    suspend fun ask(question: String, answers: List<ExpectedAnswer> = emptyList()): Heard {
        // Essai du 29/10/2026 : sur une question longue, la Voice PE rouvre son micro avant d'avoir
        // fini de parler et s'entend elle-même. Le début se dit donc en annonce (qui rend la main à
        // la fin de la lecture), et seule la dernière phrase, courte, est posée en question.
        val (preamble, short) = split(question)
        if (preamble != null && !say(preamble)) return Heard.LinkLost
        repeat(ECHO_RETRIES + 1) { attempt ->
            val answer = try {
                link.ask(satellite, short, answers)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                lastFailure = failure
                return Heard.LinkLost
            }
            val matched = answer.answerId?.let { id -> answers.firstOrNull { it.id == id } }
            if (matched != null) return Heard.Text(matched.sentences.first())
            val text = answer.sentence?.trim()
            if (text.isNullOrEmpty()) return Heard.Silence
            // L'enceinte s'est entendue : on ignore, et on repose la question courte une fois.
            if (isEcho(text, question, answers)) {
                if (attempt < ECHO_RETRIES) return@repeat
                return Heard.Silence
            }
            return Heard.Text(text)
        }
        return Heard.Silence
    }

    companion object {
        /** Au-delà, une question se coupe en annonce + question courte. */
        internal const val SHORT_QUESTION_CHARS = 70

        /** Questions reposées après un écho, sans compter comme incompréhension. */
        private const val ECHO_RETRIES = 1

        private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")

        /**
         * Coupe une question longue en (annonce, question courte) : la dernière phrase est la question.
         * Une question courte, ou d'une seule phrase, reste entière.
         */
        internal fun split(question: String): Pair<String?, String> {
            val text = question.trim()
            if (text.length <= SHORT_QUESTION_CHARS) return null to text
            val sentences = text.split(SENTENCE_END).filter { it.isNotBlank() }
            if (sentences.size < 2) return null to text
            // Essai du 29/10/2026 : une question coupée en « ? » seul a fait échouer Piper, et
            // l'enceinte est restée muette jusqu'à son redémarrage. La question garde donc toujours
            // des mots : on y rattache les phrases précédentes tant qu'elle n'en a pas.
            var cut = sentences.size - 1
            while (cut > 0 && letters(sentences.drop(cut).joinToString(" ")) < MIN_QUESTION_LETTERS) cut--
            if (cut == 0) return null to text
            return sentences.take(cut).joinToString(" ") to sentences.drop(cut).joinToString(" ")
        }

        /**
         * Vrai si [heard] reprend surtout les mots de [question] : l'enceinte s'est enregistrée
         * elle-même. Au moins deux mots, dont la moitié au moins viennent de la question. Une réponse
         * attendue telle quelle (« repas ou collation ») n'est jamais un écho.
         */
        internal fun isEcho(heard: String, question: String, answers: List<ExpectedAnswer> = emptyList()): Boolean {
            val said = words(heard)
            if (said.size < 2) return false
            val normalized = said.joinToString(" ")
            if (answers.any { a -> a.sentences.any { words(it).joinToString(" ") == normalized } }) return false
            val asked = words(question).toSet()
            return said.count { it in asked } * 2 >= said.size
        }

        /** Une question en a au moins autant : moins, ce n'est que de la ponctuation. */
        private const val MIN_QUESTION_LETTERS = 3

        private fun letters(text: String): Int = text.count { it.isLetter() }

        /** Vrai si [text] a de quoi être dit : Piper échoue sur de la ponctuation seule. */
        internal fun speakable(text: String): Boolean = letters(text) > 0

        private fun words(text: String): List<String> =
            CarbSanity.normalize(text).split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }

        /** Dit sur le téléphone quand l'enceinte ne répond plus (§ 5). */
        const val LINK_LOST = "La maison ne répond plus, je continue ici."

        /** V7 : un repas dicté au téléphone est en cours, l'enceinte ne le remplace pas. */
        const val BUSY = "Un repas est déjà en cours sur le téléphone."

        /** § 3.4 : la seule action sur le téléphone est « Confirmer » dans CamAPS. */
        const val UNLOCK_PHONE = "Déverrouille ton téléphone avec ton code ou ton empreinte, pour CamAPS."

        /** Le téléphone n'a pas été déverrouillé à temps : rien n'est écrit dans CamAPS. */
        const val STILL_LOCKED = "Le téléphone est resté verrouillé. Je n'écris rien dans CamAPS."

        /** Sans clé Mistral, le dialogue ne peut pas comprendre le repas. */
        const val NO_KEY = "La clé Mistral n'est pas enregistrée sur le téléphone. Je ne peux pas noter ce repas."

        /**
         * Réponses attendues à la question du type de repas. La première phrase de chaque réponse
         * est celle que `CamapsVoice.matchMealType` lit sans ambiguïté.
         */
        val MEAL_TYPE_ANSWERS: List<ExpectedAnswer> = listOf(
            ExpectedAnswer(
                id = "lent",
                sentences = listOf(
                    "absorption lente", "lent", "lente", "glucides lents", "repas lent",
                    "repas à glucides lents", "repas à absorption lente",
                ),
            ),
            ExpectedAnswer(
                id = "hypo",
                sentences = listOf(
                    "hypoglycémie", "hypo", "une hypo", "resucrage", "traitement de l'hypoglycémie",
                ),
            ),
            ExpectedAnswer(
                id = "repas",
                sentences = listOf(
                    "repas", "au repas", "un repas", "collation", "une collation", "repas ou collation",
                    "goûter", "snack",
                ),
            ),
        )
    }
}
