package com.najah.mealannouncer.speech

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.najah.mealannouncer.feature.assistant.LocalTranscriber
import com.najah.mealannouncer.feature.assistant.WakeWordText
import com.najah.mealannouncer.feature.assistant.WakeWordVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dictée française sur le téléphone : modèle Kroko (zipformer en flux, 68 Mo) par sherpa-onnx.
 *
 * Mesures du 09/11/2026 sur le Hammer (docs/VOIX-LOCALE.md) : 88 % des informations utiles
 * (quantités, aliments, commandes) retrouvées, ≈ 0,6 s pour une dictée de 4 s, contre 91 % pour
 * Parakeet qui pèse dix fois plus. Le chargement (≈ 8 s) est fait une seule fois, en arrière-plan,
 * dès la création : le modèle reste ensuite en mémoire.
 *
 * Sert aussi à réécouter le mot-clé « miam-miam » avant de réveiller l'appli ([WakeWordVerifier]).
 *
 * Règle héritée de l'incident du 09/11 (CamAPS tué faute de mémoire) : **un seul décodage à la
 * fois** ([mutex]), deux fils de calcul seulement.
 */
@Singleton
class KrokoSpeech @Inject constructor(
    private val models: LocalSpeechModels,
) : LocalTranscriber, WakeWordVerifier {

    private val mutex = Mutex()

    @Volatile
    private var recognizer: OnlineRecognizer? = null

    @Volatile
    private var loadFailed = false

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            mutex.withLock { load() }
        }
    }

    override val available: Boolean
        get() = !loadFailed && models.krokoAvailable

    override suspend fun transcribe(wav: ByteArray): String? {
        val pcm = pcmFromWav(wav) ?: return null
        val started = System.currentTimeMillis()
        val text = decodeGuarded(pcm) ?: return null
        Log.i(TAG, "Dictée locale (${System.currentTimeMillis() - started} ms, ${pcm.size / 32} ms d'audio) : $text")
        return text.takeIf { it.isNotBlank() }
    }

    override suspend fun confirms(pcm16k: ByteArray): Boolean {
        if (!available) return true
        val text = decodeGuarded(pcm16k) ?: return true
        val ok = WakeWordText.verifiedByTranscript(text)
        Log.i(TAG, "Réécoute du mot-clé : « $text » → ${if (ok) "confirmé" else "écarté"}")
        return ok
    }

    /** Décode sans jamais lever ; `null` si le modèle manque ou a échoué. */
    private suspend fun decodeGuarded(pcm: ByteArray): String? = withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                val rec = load() ?: return@withLock null
                decode(rec, floatsFromPcm16(pcm))
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            Log.w(TAG, "Décodage local en échec", error)
            null
        }
    }

    /** Charge le modèle une fois ; à appeler sous [mutex]. */
    private fun load(): OnlineRecognizer? {
        recognizer?.let { return it }
        if (loadFailed || !models.krokoAvailable) return null
        val dir = models.krokoDir.absolutePath
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$dir/encoder.onnx",
                    decoder = "$dir/decoder.onnx",
                    joiner = "$dir/joiner.onnx",
                ),
                tokens = "$dir/tokens.txt",
                numThreads = NUM_THREADS,
            ),
            enableEndpoint = false,
        )
        val started = System.currentTimeMillis()
        return try {
            OnlineRecognizer(config = config).also {
                recognizer = it
                Log.i(TAG, "Modèle Kroko chargé en ${System.currentTimeMillis() - started} ms")
            }
        } catch (error: Throwable) {
            loadFailed = true
            Log.w(TAG, "Modèle Kroko impossible à charger", error)
            null
        }
    }

    private fun decode(rec: OnlineRecognizer, samples: FloatArray): String {
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            // Une demi-seconde de silence pousse les derniers mots hors du modèle en flux.
            stream.acceptWaveform(FloatArray(SAMPLE_RATE / 2), SAMPLE_RATE)
            stream.inputFinished()
            while (rec.isReady(stream)) rec.decode(stream)
            return rec.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    companion object {
        const val TAG = "MealAnnouncerLocal"
        const val SAMPLE_RATE = 16_000
        private const val NUM_THREADS = 2
        private const val WAV_HEADER_BYTES = 44

        /** PCM 16 bits d'un WAV produit par l'appli (en-tête de 44 octets, 16 kHz mono). */
        internal fun pcmFromWav(wav: ByteArray): ByteArray? {
            if (wav.size <= WAV_HEADER_BYTES) return null
            val riff = String(wav, 0, 4, Charsets.US_ASCII)
            if (riff != "RIFF") return wav // déjà du PCM brut
            val rate = (wav[24].toInt() and 0xFF) or
                ((wav[25].toInt() and 0xFF) shl 8) or
                ((wav[26].toInt() and 0xFF) shl 16) or
                ((wav[27].toInt() and 0xFF) shl 24)
            if (rate != SAMPLE_RATE) return null
            return wav.copyOfRange(WAV_HEADER_BYTES, wav.size)
        }

        internal fun floatsFromPcm16(pcm: ByteArray): FloatArray {
            val out = FloatArray(pcm.size / 2)
            for (i in out.indices) {
                val lo = pcm[2 * i].toInt() and 0xFF
                val hi = pcm[2 * i + 1].toInt()
                out[i] = ((hi shl 8) or lo).toShort() / 32768f
            }
            return out
        }
    }
}
