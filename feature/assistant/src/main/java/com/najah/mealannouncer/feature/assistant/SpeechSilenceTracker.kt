package com.najah.mealannouncer.feature.assistant

import kotlin.math.sqrt

/**
 * Détecteur de fin de parole par amplitude RMS sur des échantillons PCM 16 bits (`docs/MIAM-MIAM.md`).
 *
 * Il permet d'arrêter l'enregistrement automatiquement dès que l'utilisateur a fini de parler
 * (~1,5 s de silence après la parole), sans obliger à toucher l'écran une seconde fois.
 * En mode réveil vocal (« Miam-miam » suivi de « Je t'écoute »), il coupe aussi si personne ne
 * parle au bout de [initialSilenceMaxMs].
 */
class SpeechSilenceTracker(
    private val speechRmsThreshold: Double = DEFAULT_SPEECH_RMS,
    private val silenceRmsThreshold: Double = DEFAULT_SILENCE_RMS,
    private val minSpeechMs: Long = DEFAULT_MIN_SPEECH_MS,
    private val silenceAfterSpeechMs: Long = DEFAULT_SILENCE_AFTER_SPEECH_MS,
    private val stopOnInitialSilence: Boolean = false,
    private val initialSilenceMaxMs: Long = DEFAULT_INITIAL_SILENCE_MAX_MS,
) {
    private var speechAccumulatedMs: Long = 0L
    private var silenceAccumulatedMs: Long = 0L
    private var totalElapsedMs: Long = 0L

    /** Vrai dès que la voix a dépassé [speechRmsThreshold] pendant au moins [minSpeechMs]. */
    var hasSpoken: Boolean = false
        private set

    /**
     * Traite une tranche audio d'amplitude [rms] et de durée [durationMs].
     *
     * Rend `true` quand l'enregistrement doit s'arrêter automatiquement :
     * - la personne a parlé, puis s'est tue pendant [silenceAfterSpeechMs] ;
     * - ou [stopOnInitialSilence] est actif et personne n'a parlé pendant [initialSilenceMaxMs].
     */
    fun onChunk(rms: Double, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        totalElapsedMs += durationMs
        if (rms >= speechRmsThreshold) {
            speechAccumulatedMs += durationMs
            silenceAccumulatedMs = 0L
            if (speechAccumulatedMs >= minSpeechMs) {
                hasSpoken = true
            }
            return false
        }
        if (!hasSpoken) {
            if (rms < silenceRmsThreshold) {
                speechAccumulatedMs = 0L
            }
            return stopOnInitialSilence && totalElapsedMs >= initialSilenceMaxMs
        }
        if (rms < silenceRmsThreshold) {
            silenceAccumulatedMs += durationMs
            if (silenceAccumulatedMs >= silenceAfterSpeechMs) {
                return true
            }
        } else {
            silenceAccumulatedMs = 0L
        }
        return false
    }

    fun reset() {
        speechAccumulatedMs = 0L
        silenceAccumulatedMs = 0L
        totalElapsedMs = 0L
        hasSpoken = false
    }

    companion object {
        /** Voix normale à 16 kHz mono 16 bits : ~1 200 à 8 000 ; bruit de fond : ~30 à 180. */
        const val DEFAULT_SPEECH_RMS = 450.0
        const val DEFAULT_SILENCE_RMS = 300.0
        const val DEFAULT_MIN_SPEECH_MS = 200L
        const val DEFAULT_SILENCE_AFTER_SPEECH_MS = 1_500L
        const val DEFAULT_INITIAL_SILENCE_MAX_MS = 8_000L

        /**
         * Calcule l'amplitude RMS (0..32768) d'un bloc PCM 16 bits petit-boutiste (little-endian).
         */
        fun pcmRms(buffer: ByteArray, length: Int = buffer.size): Double {
            val usable = minOf(length, buffer.size) and -2
            if (usable <= 0) return 0.0
            var sumSquares = 0.0
            val sampleCount = usable / 2
            var i = 0
            while (i < usable) {
                val low = buffer[i].toInt() and 0xFF
                val high = buffer[i + 1].toInt()
                val sample = (high shl 8) or low
                val d = sample.toDouble()
                sumSquares += d * d
                i += 2
            }
            return sqrt(sumSquares / sampleCount)
        }

        /** Durée en millisecondes d'un bloc PCM 16 bits mono à [sampleRate] Hz. */
        fun chunkDurationMs(byteCount: Int, sampleRate: Int = 16_000): Long {
            if (byteCount <= 0 || sampleRate <= 0) return 0L
            val samples = byteCount / 2
            return (samples * 1_000L) / sampleRate
        }
    }
}
