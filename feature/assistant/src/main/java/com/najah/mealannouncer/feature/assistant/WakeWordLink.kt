package com.najah.mealannouncer.feature.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arbitre d'accès au microphone entre la veille « miam-miam » et le dialogue du repas.
 *
 * Deux `AudioRecord` ouverts simultanément sur Android se disputent le micro : dès que la dictée
 * (`AndroidAudioRecorder`), la reconnaissance de réponse (`AndroidSpeechAnswers`) ou l'annonce
 * vocale d'accueil prend la parole/l'écoute, la veille « miam-miam » relâche le micro et le
 * reprend aussitôt après.
 */
@Singleton
class WakeWordArbitrator @Inject constructor() {

    private val activeHolders = AtomicInteger(0)
    private val _micBusy = MutableStateFlow(false)

    /** Vrai tant qu'un enregistrement ou un dialogue utilise le micro (ou parle avant d'écouter). */
    val micBusy: StateFlow<Boolean> = _micBusy.asStateFlow()

    fun acquireMic() {
        if (activeHolders.incrementAndGet() > 0) {
            _micBusy.value = true
        }
    }

    fun releaseMic() {
        val remaining = activeHolders.decrementAndGet().coerceAtLeast(0)
        if (remaining == 0) {
            activeHolders.set(0)
            _micBusy.value = false
        }
    }
}

/**
 * Ce que le détecteur « miam-miam » du téléphone a capté (`docs/MIAM-MIAM.md` § 1.2) :
 * - [WakeOnly] : l'utilisateur a dit « Miam-miam » puis a marqué une pause (~1,2 s) ; l'écran
 *   s'ouvre, dit « Je t'écoute. » et enregistre le repas jusqu'au silence.
 * - [WithAudio] : l'utilisateur a enchaîné sa phrase d'une traite (« Miam-miam, je mange deux
 *   pains au chocolat ») ; [wav] contient l'enregistrement complet jusqu'au silence de fin de
 *   phrase, prêt à partir chez Mistral.
 */
sealed interface WakeWordTrigger {
    data object WakeOnly : WakeWordTrigger
    data class WithAudio(val wav: ByteArray) : WakeWordTrigger
}

/** Un déclenchement « miam-miam » en attente d'être pris en charge par l'écran du repas. */
data class PendingWakeWord(
    val trigger: WakeWordTrigger,
    val triggeredAtMs: Long,
)

/** État du service d'écoute « miam-miam » sur le téléphone, pour la notification et les réglages. */
sealed interface WakeWordStatus {
    /** Écoute désactivée. */
    data object Off : WakeWordStatus

    /** Le micro écoute « miam-miam » en arrière-plan. */
    data object Listening : WakeWordStatus

    /** Mis en pause le temps d'une dictée ou d'un dialogue en cours. */
    data object Paused : WakeWordStatus

    /** Le modèle Vosk français (`vosk-model-fr`) n'est pas installé sur le téléphone. */
    data object ModelMissing : WakeWordStatus

    /** L'autorisation d'enregistrement audio (`RECORD_AUDIO`) manque ou Android a refusé le micro. */
    data object MicUnavailable : WakeWordStatus
}

/**
 * Pont entre `WakeWordService` (qui écoute « miam-miam » en veille) et `MealAssistantViewModel`
 * (qui mène la dictée et la conversation du repas).
 */
@Singleton
class WakeWordLink @Inject constructor() {

    private val _status = MutableStateFlow<WakeWordStatus>(WakeWordStatus.Off)
    val status: StateFlow<WakeWordStatus> = _status.asStateFlow()

    private val _pending = MutableStateFlow<PendingWakeWord?>(null)
    val pending: StateFlow<PendingWakeWord?> = _pending.asStateFlow()

    fun updateStatus(status: WakeWordStatus) {
        _status.value = status
    }

    fun publish(
        trigger: WakeWordTrigger,
        nowMs: Long = System.currentTimeMillis(),
    ): PendingWakeWord {
        val item = PendingWakeWord(trigger = trigger, triggeredAtMs = nowMs)
        _pending.value = item
        return item
    }

    /** Prend le déclenchement [expected] s'il est toujours en attente. */
    fun take(expected: PendingWakeWord): Boolean = _pending.compareAndSet(expected, null)

    companion object {
        /** Au-delà, un réveil non pris en charge est ignoré. */
        const val PENDING_MAX_AGE_MS = 60_000L
    }
}
