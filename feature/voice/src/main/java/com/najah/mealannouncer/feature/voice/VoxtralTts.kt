package com.najah.mealannouncer.feature.voice

import android.content.Context
import android.media.MediaPlayer
import com.najah.mealannouncer.core.datastore.VoiceSettings
import com.najah.mealannouncer.core.datastore.VoxtralSecretStore
import com.najah.mealannouncer.data.voxtral.SpeechRequest
import com.najah.mealannouncer.data.voxtral.VoxtralApi
import com.najah.mealannouncer.data.voxtral.VoxtralAudioFormat
import com.najah.mealannouncer.data.voxtral.VoxtralVoice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Singleton

/**
 * La voix Voxtral : synthèse réseau, puis lecture du MP3.
 *
 * Deux principes gouvernent cette classe, et ils viennent de la passation :
 *
 * 1. **Une annonce ne dépend jamais du réseau.** La synthèse est lancée dans un scope qui ne
 *    dépend pas de l'appelant, avec un budget court ([SYNTHESIS_BUDGET_MS]). Passé ce délai,
 *    [speak] répond `false` et l'appelant bascule sur la voix Android : L'utilisateur entend son repas,
 *    même si Mistral est lent, hors ligne, ou sans quota. Le résultat tardif n'est pas perdu
 *    pour autant — il part au cache et servira à l'annonce suivante.
 * 2. **Aucune exception ne sort d'ici.** Un `false` suffit à décrire tous les échecs ; laissée
 *    filer, une `IOException` empêcherait le repli et laisserait un silence.
 */
@Singleton
class VoxtralTts @Inject constructor(
    private val settings: VoiceSettings,
    private val secrets: VoxtralSecretStore,
    private val api: VoxtralApi,
    @ApplicationContext context: Context,
) : VoiceEngineTts {

    private val cache = VoiceAudioCache(File(context.cacheDir, CACHE_DIRECTORY))

    /**
     * Le scope de synthèse est volontairement détaché : `SupervisorJob` empêche qu'un échec
     * arrête les synthèses suivantes, et `Dispatchers.IO` garde le réseau hors du fil principal.
     */
    private val synthesisScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Dernière cause d'échec, pour l'écran d'essai. Jamais une clé, jamais un journal. */
    @Volatile
    private var issue: String? = null

    override val lastIssue: String?
        get() = issue

    override suspend fun speak(text: String): Boolean {
        issue = null
        // Le contrat de cette classe est qu'aucune exception n'en sort : sans ce filet, une
        // panne inattendue serait avalée par l'appelant, qui n'aurait alors aucun diagnostic
        // (`lastIssue` resterait vide) et se rabattrait sur Android sans qu'on sache pourquoi.
        return try {
            speakGuarded(text)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            issue = "panne interne (${error::class.simpleName}) : ${error.message.orEmpty()}"
            false
        }
    }

    private suspend fun speakGuarded(text: String): Boolean {
        if (text.isBlank()) return false
        // La clé ne sort du Keystore qu'ici, le temps de l'appel ; elle n'est jamais journalisée.
        val key = secrets.read()?.takeIf { it.isNotBlank() }
        if (key == null) {
            issue = "aucune clé Mistral enregistrée"
            return false
        }
        val voice = VoxtralVoice.fromId(settings.config.value.voiceId)
        val format = VoxtralAudioFormat.DEFAULT

        val cached = cache.fileFor(text, voice, format)
        if (cached.isFile && cached.length() > 0L) {
            return play(cached)
        }

        val audio = synthesize(text, voice, format, key)
        if (audio == null) {
            // `synthesize` a déjà posé la cause (délai, refus, panne réseau).
            return false
        }
        val file = runCatching { cache.write(text, voice, format, audio) }.getOrNull()
        if (file == null) {
            issue = "audio reçu mais impossible à écrire dans le cache"
            return false
        }
        return play(file)
    }

    /**
     * Lance la synthèse et attend au plus [SYNTHESIS_BUDGET_MS].
     *
     * En cas de dépassement on rend `null` sans annuler le travail : c'est lui qui remplira le
     * cache pour la fois d'après. `await()` sur une `CompletableDeferred` complétée par un autre
     * scope ne bloque pas la coroutine appelante, seulement jusqu'au délai.
     */
    private suspend fun synthesize(
        text: String,
        voice: VoxtralVoice,
        format: VoxtralAudioFormat,
        key: String,
    ): ByteArray? {
        val outcome = CompletableDeferred<Result<ByteArray>>()
        val request = SpeechRequest(input = text, voice = voice, format = format)
        synthesisScope.launch {
            val result = runCatching { api.synthesize(key, request) }
            val bytes = result.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                runCatching { cache.write(text, voice, format, bytes) }
            }
            outcome.complete(result)
        }
        val result = withTimeoutOrNull(SYNTHESIS_BUDGET_MS) { outcome.await() }
        if (result == null) {
            issue = "Voxtral n'a pas répondu en ${SYNTHESIS_BUDGET_MS / 1000} s"
            return null
        }
        val bytes = result.getOrNull()
        if (bytes == null) {
            issue = result.exceptionOrNull()?.message ?: "Voxtral a échoué sans explication"
            return null
        }
        if (bytes.isEmpty()) {
            issue = "Voxtral a renvoyé un audio vide"
            return null
        }
        return bytes
    }

    /**
     * Joue le fichier et rend `true` dès que la lecture a **commencé**.
     *
     * Une fois que Voxtral parle, l'appelant ne doit plus basculer sur la voix Android : sinon la
     * même phrase est dite deux fois, par deux voix (constaté le 27/10). `false` ne sort donc que
     * d'un fichier illisible ou d'une erreur avant le début du son.
     *
     * `MediaPlayer` doit être créé et piloté depuis un fil qui a un `Looper` : d'où le passage
     * sur le fil principal. On attend la fin de la lecture pendant la **durée réelle** du MP3
     * (lue après `prepare()`), plus une marge : une estimation par la taille du fichier se
     * trompait d'un facteur deux (MP3 Voxtral à ~60 kbit/s) et coupait les longues phrases.
     */
    private suspend fun play(file: File): Boolean = withContext(Dispatchers.Main) {
        val finished = CompletableDeferred<Boolean>()
        var player: MediaPlayer? = null
        var started = false
        try {
            player = MediaPlayer().apply {
                setOnCompletionListener { finished.complete(true) }
                setOnErrorListener { _, _, _ ->
                    finished.complete(false)
                    true
                }
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
            started = true
        } catch (error: Exception) {
            // Fichier tronqué, codec absent, source illisible : tout se résume à « pas joué ».
            issue = "audio illisible sur ce téléphone"
            finished.complete(false)
        }
        val timeout = playbackTimeout(player?.duration ?: 0)
        val completed = withTimeoutOrNull(timeout) { finished.await() }
        runCatching { player?.release() }
        when {
            completed == true -> true
            // Le son est parti : redire la phrase avec Android ferait doublon.
            started && completed == null -> {
                issue = "lecture plus longue que prévu (${timeout / 1000} s)"
                true
            }
            else -> {
                issue = issue ?: "la lecture de l'audio n'a pas abouti"
                false
            }
        }
    }

    /** Durée réelle du MP3 plus une marge ; à défaut de durée connue, le plafond. */
    private fun playbackTimeout(durationMs: Int): Long {
        if (durationMs <= 0) return MAX_PLAYBACK_MS
        return (durationMs + PLAYBACK_MARGIN_MS).coerceIn(5_000L, MAX_PLAYBACK_MS)
    }

    private companion object {
        const val CACHE_DIRECTORY = "voxtral"

        /** Budget de la synthèse : au-delà, la voix Android prend le relais. */
        const val SYNTHESIS_BUDGET_MS = 2_500L

        const val MAX_PLAYBACK_MS = 60_000L

        /** Marge au-delà de la durée du MP3 : démarrage du lecteur et fin de piste. */
        const val PLAYBACK_MARGIN_MS = 2_000L
    }
}
