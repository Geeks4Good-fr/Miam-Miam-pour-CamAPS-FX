package com.najah.mealannouncer.feature.assistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Le micro de la dictée.
 *
 * L'interface existe pour que le reste du module ne dépende pas d'Android : les tests
 * éprouvent le calcul, pas l'enregistrement.
 */
interface MealAudioRecorder {

    /** Ouvre le micro. Faux si l'appareil refuse (permission, micro occupé). */
    fun start(): Boolean

    /**
     * Ouvre le micro et appelle [onSilenceReached] dès qu'un silence de fin de phrase (~1,5 s
     * après la parole, ou [stopOnInitialSilence] sans parole) est détecté.
     */
    fun start(
        stopOnInitialSilence: Boolean = false,
        onSilenceReached: (() -> Unit)? = null,
    ): Boolean = start()

    /** Coupe le micro et rend un WAV prêt à être envoyé (vide si rien n'a été capté). */
    fun stop(): ByteArray

    /** Coupe le micro et jette ce qui a été capté. */
    fun cancel()
}

/**
 * Enregistrement au format WAV 16 kHz mono 16 bits.
 *
 * Ce format est le plus universellement accepté par les API de transcription, et il se
 * fabrique sans bibliothèque : 44 octets d'en-tête, puis les échantillons bruts.
 */
class AndroidAudioRecorder @Inject constructor(
    private val arbitrator: WakeWordArbitrator,
) : MealAudioRecorder {

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    private val micAcquired = AtomicBoolean(false)
    private val samples = ByteArrayOutputStream()

    override fun start(): Boolean = start(stopOnInitialSilence = false, onSilenceReached = null)

    @SuppressLint("MissingPermission") // L'écran ne demande l'enregistrement qu'après accord.
    override fun start(
        stopOnInitialSilence: Boolean,
        onSilenceReached: (() -> Unit)?,
    ): Boolean {
        if (running) return true
        if (micAcquired.compareAndSet(false, true)) {
            arbitrator.acquireMic()
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            releaseArbitrator()
            return false
        }
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, BUFFER_BYTES),
            )
        } catch (error: IllegalArgumentException) {
            // Matériel qui refuse la configuration demandée.
            releaseArbitrator()
            return false
        } catch (error: SecurityException) {
            // Autorisation retirée entre l'écran et l'ouverture du micro.
            releaseArbitrator()
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            releaseArbitrator()
            return false
        }
        samples.reset()
        record = recorder
        running = true
        return try {
            recorder.startRecording()
            thread = Thread(
                { pump(recorder, stopOnInitialSilence, onSilenceReached) },
                "meal-dictation",
            ).apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: IllegalStateException) {
            running = false
            recorder.release()
            record = null
            releaseArbitrator()
            false
        } catch (error: SecurityException) {
            running = false
            recorder.release()
            record = null
            releaseArbitrator()
            false
        }
    }

    private fun pump(
        recorder: AudioRecord,
        stopOnInitialSilence: Boolean,
        onSilenceReached: (() -> Unit)?,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        val tracker = if (onSilenceReached != null) {
            SpeechSilenceTracker(stopOnInitialSilence = stopOnInitialSilence)
        } else {
            null
        }
        var silenceNotified = false
        while (running) {
            val read = try {
                recorder.read(buffer, 0, buffer.size)
            } catch (error: IllegalStateException) {
                break
            }
            if (read <= 0) break
            synchronized(samples) { samples.write(buffer, 0, read) }
            if (tracker != null && !silenceNotified) {
                val rms = SpeechSilenceTracker.pcmRms(buffer, read)
                val durationMs = SpeechSilenceTracker.chunkDurationMs(read, SAMPLE_RATE)
                if (tracker.onChunk(rms, durationMs)) {
                    silenceNotified = true
                    onSilenceReached?.invoke()
                }
            }
        }
    }

    override fun stop(): ByteArray {
        finish()
        val pcm = synchronized(samples) { samples.toByteArray() }
        samples.reset()
        return if (pcm.isEmpty()) ByteArray(0) else wavFromPcm16(pcm)
    }

    override fun cancel() {
        finish()
        synchronized(samples) { samples.reset() }
    }

    private fun finish() {
        running = false
        if (Thread.currentThread() !== thread) {
            thread?.join(RELEASE_TIMEOUT_MS)
        }
        thread = null
        record?.let { recorder ->
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            } catch (error: IllegalStateException) {
                // Arrêt déjà fait par le système : rien à ajouter.
            } finally {
                recorder.release()
            }
        }
        record = null
        releaseArbitrator()
    }

    private fun releaseArbitrator() {
        if (micAcquired.compareAndSet(true, false)) {
            arbitrator.releaseMic()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val BUFFER_BYTES = 4_000
        const val RELEASE_TIMEOUT_MS = 2_000L
    }
}

/**
 * Enveloppe des échantillons PCM 16 bits dans un fichier WAV.
 *
 * Fonction pure et testée : une en-tête fausse d'un octet et l'API de transcription reçoit
 * du bruit.
 */
internal fun wavFromPcm16(pcm: ByteArray, sampleRate: Int = 16_000): ByteArray {
    val channels = 1
    val bitsPerSample = 16
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val dataSize = pcm.size
    val out = ByteArrayOutputStream(44 + dataSize)

    fun text(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
    fun int32(value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
    }
    fun int16(value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    text("RIFF")
    int32(36 + dataSize)
    text("WAVE")
    text("fmt ")
    int32(16)
    int16(1) // PCM
    int16(channels)
    int32(sampleRate)
    int32(byteRate)
    int16(blockAlign)
    int16(bitsPerSample)
    text("data")
    int32(dataSize)
    out.write(pcm)
    return out.toByteArray()
}
