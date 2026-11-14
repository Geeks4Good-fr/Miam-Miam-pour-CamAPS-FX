package com.najah.mealannouncer.feature.assistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Détecteur vocal hors ligne du mot-clé « miam-miam » (`docs/MIAM-MIAM.md`).
 *
 * Il écoute le micro en continu avec Vosk (grammaire restreinte à `["miam-miam", "miam miam",
 * "miam", "[unk]"]`). Dès que « miam-miam » est détecté :
 * - il observe le même flux `AudioRecord` pendant [FOLLOW_ON_WINDOW_MS] (1,2 s) ;
 * - si la personne continue de parler dans cette fenêtre (« Miam-miam, je mange deux pains au
 *   chocolat »), il enregistre la phrase jusqu'à [SILENCE_AFTER_SPEECH_MS] (1,5 s) de silence et
 *   émet [WakeWordTrigger.WithAudio] ;
 * - si la personne marque une pause après « Miam-miam », il relâche le micro et émet
 *   [WakeWordTrigger.WakeOnly] pour que l'application dise « Je t'écoute. » et écoute le repas.
 */
@Singleton
class WakeWordDetector @Inject constructor(
    private val modelStorage: VoskModelStorage,
    private val arbitrator: WakeWordArbitrator,
    private val link: WakeWordLink,
    private val verifier: WakeWordVerifier,
) {
    /**
     * Boucle de veille : tourne jusqu'à l'annulation de la coroutine, ou s'arrête si le modèle
     * Vosk est absent. Se met automatiquement en pause quand [WakeWordArbitrator.micBusy] est vrai.
     */
    suspend fun run(onTriggered: (WakeWordTrigger) -> Unit) = withContext(Dispatchers.IO) {
        if (!modelStorage.available) {
            Log.w(TAG, "Modèle Vosk absent : ${modelStorage.directory().absolutePath}")
            link.updateStatus(WakeWordStatus.ModelMissing)
            return@withContext
        }
        runCatching { LibVosk.setLogLevel(LogLevel.WARNINGS) }
        val model = try {
            Model(modelStorage.directory().absolutePath)
        } catch (error: IOException) {
            Log.w(TAG, "Impossible de charger le modèle Vosk", error)
            link.updateStatus(WakeWordStatus.ModelMissing)
            return@withContext
        } catch (error: UnsatisfiedLinkError) {
            Log.w(TAG, "Bibliothèque native Vosk indisponible", error)
            link.updateStatus(WakeWordStatus.ModelMissing)
            return@withContext
        }

        try {
            while (currentCoroutineContext().isActive) {
                if (arbitrator.micBusy.value) {
                    link.updateStatus(WakeWordStatus.Paused)
                    delay(PAUSE_POLL_MS)
                    continue
                }
                val trigger = listenUntilWakeOrPause(model)
                if (trigger != null) {
                    link.updateStatus(WakeWordStatus.Paused)
                    onTriggered(trigger)
                    // Laisse à l'écran et au ViewModel le temps de prendre le micro avant de
                    // rouvrir la veille.
                    delay(COOLDOWN_AFTER_TRIGGER_MS)
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } finally {
            runCatching { model.close() }
            link.updateStatus(WakeWordStatus.Off)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun listenUntilWakeOrPause(model: Model): WakeWordTrigger? {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            link.updateStatus(WakeWordStatus.MicUnavailable)
            delay(RETRY_MIC_MS)
            return null
        }
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, BUFFER_BYTES * 2),
            )
        } catch (error: Exception) {
            Log.w(TAG, "Ouverture du micro refusée", error)
            link.updateStatus(WakeWordStatus.MicUnavailable)
            delay(RETRY_MIC_MS)
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            link.updateStatus(WakeWordStatus.MicUnavailable)
            delay(RETRY_MIC_MS)
            return null
        }

        val recognizer = try {
            Recognizer(model, SAMPLE_RATE.toFloat(), WakeWordText.VOSK_GRAMMAR)
        } catch (error: Exception) {
            recorder.release()
            Log.w(TAG, "Initialisation du Recognizer Vosk refusée", error)
            delay(RETRY_MIC_MS)
            return null
        }

        try {
            recorder.startRecording()
        } catch (error: Exception) {
            recognizer.close()
            recorder.release()
            link.updateStatus(WakeWordStatus.MicUnavailable)
            delay(RETRY_MIC_MS)
            return null
        }

        link.updateStatus(WakeWordStatus.Listening)
        Log.i(TAG, "Veille « miam-miam » active")

        val buffer = ByteArray(BUFFER_BYTES)
        // Garde les ~400 dernières ms de PCM pour ne pas couper la première syllabe qui suit « miam-miam ».
        val preRoll = ArrayDeque<ByteArray>(PRE_ROLL_CHUNKS)
        // Les ~2,5 dernières secondes, réécoutées par le modèle de dictée avant de réveiller l'appli.
        val history = ArrayDeque<ByteArray>(VERIFY_HISTORY_CHUNKS)

        try {
            while (currentCoroutineContext().isActive && !arbitrator.micBusy.value) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    delay(50L)
                    continue
                }
                val chunk = buffer.copyOf(read)
                if (preRoll.size >= PRE_ROLL_CHUNKS) preRoll.removeFirst()
                preRoll.addLast(chunk)
                if (history.size >= VERIFY_HISTORY_CHUNKS) history.removeFirst()
                history.addLast(chunk)

                val isFinal = recognizer.acceptWaveForm(chunk, read)
                val json = if (isFinal) recognizer.result else recognizer.partialResult
                if (WakeWordText.containsWakeWord(json)) {
                    Log.i(TAG, "Mot-clé « miam-miam » détecté : $json")
                    recognizer.reset()
                    val followOn = ByteArrayOutputStream()
                    val trigger = captureFollowOnOrWakeOnly(recorder, preRoll, followOn)
                    if (confirmed(history, followOn.toByteArray())) return trigger
                    // Fausse alerte (télévision, conversation) : on se rendort sans rien dire.
                    preRoll.clear()
                    history.clear()
                    link.updateStatus(WakeWordStatus.Listening)
                }
            }
            return null
        } finally {
            runCatching { recognizer.close() }
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            }
            runCatching { recorder.release() }
        }
    }

    /**
     * Après « miam-miam », observe le flux audio pendant [FOLLOW_ON_WINDOW_MS] :
     * - si la personne enchaîne sa phrase, continue d'enregistrer jusqu'à 1,5 s de silence et
     *   rend [WakeWordTrigger.WithAudio] ;
     * - sinon, rend [WakeWordTrigger.WakeOnly].
     */
    /**
     * Réécoute par le modèle de dictée local : les ~2,5 s qui précèdent la détection plus le
     * début de la suite. Essai du 09/11/2026 sur 168 phrases de télévision : 0 fausse alerte
     * retenue. Sans modèle local, [WakeWordVerifier.confirms] rend `true` (comportement d'avant).
     */
    private suspend fun confirmed(history: ArrayDeque<ByteArray>, followOn: ByteArray): Boolean {
        if (!verifier.available) return true
        val out = ByteArrayOutputStream()
        for (chunk in history) out.write(chunk)
        out.write(followOn, 0, minOf(followOn.size, VERIFY_FOLLOW_ON_BYTES))
        val ok = verifier.confirms(out.toByteArray())
        if (!ok) Log.i(TAG, "Fausse alerte écartée par la réécoute : pas de « miam » entendu")
        return ok
    }

    private suspend fun captureFollowOnOrWakeOnly(
        recorder: AudioRecord,
        preRoll: ArrayDeque<ByteArray>,
        followOn: ByteArrayOutputStream,
    ): WakeWordTrigger {
        val pcmOut = ByteArrayOutputStream()
        for (chunk in preRoll) {
            pcmOut.write(chunk)
        }
        val tracker = SpeechSilenceTracker(
            speechRmsThreshold = SpeechSilenceTracker.DEFAULT_SPEECH_RMS,
            silenceRmsThreshold = SpeechSilenceTracker.DEFAULT_SILENCE_RMS,
            minSpeechMs = 150L,
            silenceAfterSpeechMs = SILENCE_AFTER_SPEECH_MS,
            stopOnInitialSilence = true,
            initialSilenceMaxMs = FOLLOW_ON_WINDOW_MS,
        )
        val buffer = ByteArray(BUFFER_BYTES)
        var elapsedMs = 0L
        while (currentCoroutineContext().isActive && !arbitrator.micBusy.value && elapsedMs < MAX_FOLLOW_ON_RECORDING_MS) {
            val read = recorder.read(buffer, 0, buffer.size)
            if (read <= 0) break
            pcmOut.write(buffer, 0, read)
            followOn.write(buffer, 0, read)
            val rms = SpeechSilenceTracker.pcmRms(buffer, read)
            val chunkMs = SpeechSilenceTracker.chunkDurationMs(read, SAMPLE_RATE)
            elapsedMs += chunkMs
            if (tracker.onChunk(rms, chunkMs)) {
                break
            }
        }
        return if (tracker.hasSpoken) {
            Log.i(TAG, "Suite de phrase captée après « miam-miam » (${elapsedMs} ms)")
            WakeWordTrigger.WithAudio(wavFromPcm16(pcmOut.toByteArray(), SAMPLE_RATE))
        } else {
            Log.i(TAG, "Pause après « miam-miam » : passage en écoute « Je t'écoute »")
            WakeWordTrigger.WakeOnly
        }
    }

    companion object {
        const val TAG = "MealAnnouncerWake"
        private const val SAMPLE_RATE = 16_000
        /** 4 000 octets = 2 000 échantillons 16 bits = 125 ms par lecture. */
        private const val BUFFER_BYTES = 4_000
        private const val PRE_ROLL_CHUNKS = 3
        /** 20 × 125 ms = 2,5 s d'historique pour la réécoute. */
        private const val VERIFY_HISTORY_CHUNKS = 20
        /** 1,5 s de la suite (16 000 éch./s × 2 octets × 1,5). */
        private const val VERIFY_FOLLOW_ON_BYTES = 48_000
        private const val FOLLOW_ON_WINDOW_MS = 1_200L
        private const val SILENCE_AFTER_SPEECH_MS = 1_500L
        private const val MAX_FOLLOW_ON_RECORDING_MS = 25_000L
        private const val PAUSE_POLL_MS = 200L
        private const val RETRY_MIC_MS = 2_000L
        private const val COOLDOWN_AFTER_TRIGGER_MS = 1_500L
    }
}
