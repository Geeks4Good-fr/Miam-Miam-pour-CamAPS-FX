package com.najah.mealannouncer.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.najah.mealannouncer.feature.voice.VoiceEngineTts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Voix locale Piper « upmc » (fr_FR, locutrice 0), calculée sur le téléphone par sherpa-onnx.
 *
 * Choisie par l'utilisateur à l'écoute le 09/11/2026. Mesures sur le Hammer : 0,5 à 2,6 s de calcul par
 * annonce, aucun réseau, aucune clé. Le modèle est chargé à la première annonce puis gardé.
 *
 * `noise_scale` est abaissé à 0,333 (0,667 par défaut) : essai du 09/11, « glucides » bien
 * prononcé 10 fois sur 12 au lieu de 7 (sinon « bucide »).
 *
 * Comme les autres moteurs, aucune exception ne sort : `false` et [lastIssue] suffisent, la voix
 * Android prend alors le relais.
 */
@Singleton
class PiperTts @Inject constructor(
    private val models: LocalSpeechModels,
) : VoiceEngineTts {

    private val mutex = Mutex()

    @Volatile
    private var tts: OfflineTts? = null

    @Volatile
    private var issue: String? = null

    override val lastIssue: String?
        get() = issue

    override suspend fun speak(text: String): Boolean {
        issue = null
        if (text.isBlank()) return false
        return try {
            val started = System.currentTimeMillis()
            val audio = withContext(Dispatchers.IO) { mutex.withLock { synthesize(text) } } ?: return false
            Log.i(KrokoSpeech.TAG, "Piper : « $text » calculé en ${System.currentTimeMillis() - started} ms")
            play(audio)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            issue = "voix locale en panne (${error::class.simpleName}) : ${error.message.orEmpty()}"
            Log.w(KrokoSpeech.TAG, "Piper en échec", error)
            false
        }
    }

    /** À appeler sous [mutex]. */
    private fun synthesize(text: String): GeneratedAudio? {
        val engine = tts ?: load() ?: return null
        val audio = engine.generate(text, SPEAKER_ID, 1.0f)
        if (audio.samples.isEmpty()) {
            issue = "la voix locale n'a produit aucun son"
            return null
        }
        return audio
    }

    private fun load(): OfflineTts? {
        val model = models.piperModel
        if (model == null || !models.piperAvailable) {
            issue = "modèle Piper absent (${models.piperDir.absolutePath})"
            return null
        }
        val dir = models.piperDir.absolutePath
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.absolutePath,
                    tokens = "$dir/tokens.txt",
                    dataDir = "$dir/espeak-ng-data",
                    noiseScale = NOISE_SCALE,
                ),
                numThreads = NUM_THREADS,
            ),
        )
        val started = System.currentTimeMillis()
        return OfflineTts(config = config).also {
            tts = it
            Log.i(KrokoSpeech.TAG, "Voix Piper chargée en ${System.currentTimeMillis() - started} ms")
        }
    }

    /**
     * Joue les échantillons et attend la fin. `true` dès que le son est parti : redire la phrase
     * avec la voix Android ferait doublon (même règle que Voxtral).
     *
     * PCM 16 bits envoyé en flux (`MODE_STREAM`) : le Hammer (MediaTek) refusait la piste
     * `MODE_STATIC` en flottants — « sortie audio indisponible », essai du 09/11/2026.
     */
    private suspend fun play(audio: GeneratedAudio): Boolean = withContext(Dispatchers.IO) {
        // Un court silence en tête : une enceinte Bluetooth qui se réveille mange sinon le début.
        val lead = audio.sampleRate * LEAD_SILENCE_MS / 1000
        val pcm = ShortArray(lead + audio.samples.size)
        audio.samples.forEachIndexed { i, x ->
            pcm[lead + i] = (x.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            audio.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            issue = "sortie audio indisponible (tampon $minBuffer)"
            return@withContext false
        }
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(audio.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuffer * 4)
                .build()
        } catch (error: Exception) {
            issue = "sortie audio refusée (${error::class.simpleName})"
            return@withContext false
        }
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                issue = "sortie audio indisponible"
                return@withContext false
            }
            track.play()
            var offset = 0
            while (offset < pcm.size) {
                val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) {
                    issue = "sortie audio interrompue ($written)"
                    return@withContext offset > 0
                }
                offset += written
            }
            // Le dernier tampon est encore en cours de lecture : on attend qu'il soit joué.
            val deadline = System.currentTimeMillis() + PLAYBACK_MARGIN_MS +
                minBuffer * 4L * 1000L / (2L * audio.sampleRate)
            while (track.playbackHeadPosition < pcm.size && System.currentTimeMillis() < deadline) {
                delay(POLL_MS)
            }
            true
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private companion object {
        const val SPEAKER_ID = 0
        const val NOISE_SCALE = 0.333f
        const val NUM_THREADS = 2
        const val LEAD_SILENCE_MS = 150
        const val PLAYBACK_MARGIN_MS = 1_500L
        const val POLL_MS = 50L
    }
}
