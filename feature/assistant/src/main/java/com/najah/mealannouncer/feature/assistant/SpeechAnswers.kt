package com.najah.mealannouncer.feature.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * La parole de l'utilisateur, question par question.
 *
 * C'est la reconnaissance vocale **du téléphone** : elle est gratuite, souvent disponible
 * hors ligne, et elle ne fait pas voyager l'audio par l'application. L'interface existe pour
 * que le dialogue ne dépende pas d'Android, et pour qu'un appareil sans reconnaissance
 * vocale puisse basculer sur la dictée Voxtral sans changer une ligne du dialogue.
 */
interface SpeechAnswers {

    /** Vrai si l'appareil a un service de reconnaissance vocale utilisable. */
    val available: Boolean

    /**
     * Écoute une réponse et rend ce qui a été reconnu, ou `null` si rien n'est venu
     * (silence, bruit, service absent, délai dépassé).
     *
     * [onPartial] reçoit les mots au fur et à mesure : l'écran peut les afficher, ce qui
     * montre que le micro est bien ouvert.
     */
    suspend fun listen(onPartial: (String) -> Unit): String?

    /** Coupe l'écoute en cours, s'il y en a une. */
    fun stop()
}

/**
 * Implémentation Android, par `SpeechRecognizer`.
 *
 * Deux contraintes de la plateforme expliquent la forme du code : le moteur doit être créé et
 * piloté depuis le fil principal, et il rend son résultat par rappels (d'où la coroutine
 * suspendue). Il s'arrête de lui-même après une phrase : c'est le dialogue qui le relance
 * pour la question suivante, ce qui est exactement ce qu'on veut ici.
 */
@Singleton
class AndroidSpeechAnswers @Inject constructor(
    @ApplicationContext private val context: Context,
    private val arbitrator: WakeWordArbitrator,
) : SpeechAnswers {

    @Volatile
    private var recognizer: SpeechRecognizer? = null

    private val micAcquired = AtomicBoolean(false)

    override val available: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    override suspend fun listen(onPartial: (String) -> Unit): String? {
        if (!available) return null
        if (micAcquired.compareAndSet(false, true)) {
            arbitrator.acquireMic()
        }
        return try {
            withContext(Dispatchers.Main) {
                withTimeoutOrNull(LISTEN_TIMEOUT_MS) {
                    suspendCancellableCoroutine<String?> { continuation ->
                        val speech = try {
                            SpeechRecognizer.createSpeechRecognizer(context)
                        } catch (error: RuntimeException) {
                            continuation.resume(null)
                            return@suspendCancellableCoroutine
                        }
                        recognizer = speech
                        var termine = false

                        // Une lambda plutôt qu'une fonction locale : elle est capturée par
                        // l'objet ci-dessous sans dépendre des règles de capture des fonctions
                        // locales, qui changent d'un compilateur Kotlin à l'autre.
                        val finir: (String?) -> Unit = { valeur ->
                            if (!termine) {
                                termine = true
                                runCatching { speech.destroy() }
                                if (recognizer === speech) recognizer = null
                                if (continuation.isActive) continuation.resume(valeur)
                            }
                        }

                        speech.setRecognitionListener(object : RecognitionListener {
                            override fun onReadyForSpeech(params: Bundle?) = Unit

                            override fun onBeginningOfSpeech() = Unit

                            override fun onRmsChanged(rmsdB: Float) = Unit

                            override fun onBufferReceived(buffer: ByteArray?) = Unit

                            override fun onEndOfSpeech() = Unit

                            override fun onError(error: Int) = finir(null)

                            override fun onResults(results: Bundle?) = finir(meilleur(results))

                            override fun onPartialResults(partialResults: Bundle?) {
                                meilleur(partialResults)?.let(onPartial)
                            }

                            override fun onEvent(eventType: Int, params: Bundle?) = Unit
                        })
                        continuation.invokeOnCancellation { finir(null) }
                        try {
                            speech.startListening(requete())
                        } catch (error: RuntimeException) {
                            finir(null)
                        }
                    }
                }
            }
        } finally {
            releaseArbitrator()
        }
    }

    override fun stop() {
        releaseArbitrator()
        val speech = recognizer ?: return
        recognizer = null
        // `destroy` doit passer par le fil principal, même quand l'écran se ferme.
        Handler(Looper.getMainLooper()).post { runCatching { speech.destroy() } }
    }

    private fun releaseArbitrator() {
        if (micAcquired.compareAndSet(true, false)) {
            arbitrator.releaseMic()
        }
    }

    /** La meilleure hypothèse de reconnaissance, ou `null` s'il n'y en a pas. */
    private fun meilleur(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()

    private fun requete(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fr-FR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        // Une réponse de portion est courte : on coupe vite après la dernière syllabe, sans
        // laisser le moteur attendre une suite qui ne viendra pas.
        putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
            SILENCE_MS,
        )
        putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
            SILENCE_MS,
        )
    }

    private companion object {
        /** Au-delà, on considère que personne n'a répondu. */
        const val LISTEN_TIMEOUT_MS = 20_000L

        /** Silence qui clôt une réponse : une seconde et demie, ni trop tôt ni trop long. */
        const val SILENCE_MS = 1_500L
    }
}
