package com.najah.mealannouncer.feature.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La voix du système Android.
 *
 * C'est le **repli garanti** : aucune permission, aucun réseau, aucune clé. Elle n'est pas
 * aussi belle que Voxtral, mais une annonce qui sort vaut mieux qu'une annonce parfaite qui
 * n'arrive pas.
 *
 * Le moteur est créé à la première annonce puis gardé : l'initialisation prend quelques
 * centaines de millisecondes, et on ne veut pas la repayer à chaque repas. Un échec
 * d'initialisation (aucune voix française installée, par exemple) rend `false` sans lever
 * d'exception.
 */
@Singleton
class AndroidTts @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceEngineTts {

    private val mutex = Mutex()

    private var engine: TextToSpeech? = null

    @Volatile
    private var issue: String? = null

    override val lastIssue: String?
        get() = issue

    override suspend fun speak(text: String): Boolean = mutex.withLock {
        issue = null
        val tts = engineReady()
        if (tts == null) {
            issue = issue ?: "aucune voix française disponible sur ce téléphone"
            return@withLock false
        }

        val utteranceId = "annonce-${UUID.randomUUID()}"
        val finished = CompletableDeferred<Boolean>()
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    finished.complete(true)
                }

                @Deprecated("Remplacé par onError(utteranceId, errorCode)", ReplaceWith(""))
                override fun onError(utteranceId: String?) {
                    finished.complete(false)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    finished.complete(false)
                }
            },
        )

        val queued = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (queued != TextToSpeech.SUCCESS) {
            issue = "la synthèse du téléphone a refusé la phrase"
            finished.complete(false)
        }
        // On attend la fin pour savoir si la phrase a réellement été lue ; le plafond évite
        // de retenir l'appelant si le moteur reste muet.
        val played = withTimeoutOrNull(timeoutFor(text)) { finished.await() } ?: false
        if (!played) {
            issue = issue ?: "la synthèse du téléphone s'est tue"
        }
        played
    }

    /** Crée le moteur au besoin et attend son initialisation, sans jamais dépasser [INIT_TIMEOUT_MS]. */
    private suspend fun engineReady(): TextToSpeech? = withContext(Dispatchers.Main) {
        engine?.let { return@withContext it }

        val status = CompletableDeferred<Int>()
        val created = TextToSpeech(context) { code -> status.complete(code) }
        val code = withTimeoutOrNull(INIT_TIMEOUT_MS) { status.await() } ?: TextToSpeech.ERROR
        if (code != TextToSpeech.SUCCESS) {
            issue = "la synthèse du téléphone ne répond pas"
            runCatching { created.shutdown() }
            return@withContext null
        }

        val language = created.setLanguage(Locale.FRENCH)
        val usable = language != TextToSpeech.LANG_MISSING_DATA &&
            language != TextToSpeech.LANG_NOT_SUPPORTED
        if (!usable) {
            issue = "aucune voix française installée sur ce téléphone"
            runCatching { created.shutdown() }
            return@withContext null
        }

        engine = created
        created
    }

    /** Plafond d'attente, proportionné à la longueur de la phrase (une annonce dure ~3 s). */
    private fun timeoutFor(text: String): Long =
        (MIN_TIMEOUT_MS + text.length * 120L).coerceAtMost(MAX_TIMEOUT_MS)

    private companion object {
        const val INIT_TIMEOUT_MS = 5_000L
        const val MIN_TIMEOUT_MS = 5_000L
        const val MAX_TIMEOUT_MS = 30_000L
    }
}
