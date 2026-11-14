package com.najah.mealannouncer.feature.voice

import com.najah.mealannouncer.core.datastore.VoiceSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** Qui a réellement parlé du repas. Rend compte à l'appelant, qui décide quoi en faire. */
enum class SpokenBy { VOXTRAL, PIPER, ANDROID, NONE }

/**
 * L'aiguillage de la parole : Voxtral si l'utilisateur l'a voulu, sinon la voix Android.
 *
 * Règle de sûreté de l'application : **une annonce de repas ne dépend jamais du réseau**. Si
 * Voxtral n'est pas configuré, ou si la synthèse dépasse son budget, on parle avec la voix
 * Android, qui fonctionne hors ligne. Le pire résultat possible est « rien », jamais « rien
 * alors qu'une voix locale existait ».
 *
 * Cette classe ne journalise pas et ne lève pas : elle rend [SpokenBy] et laisse le soin de
 * compter et d'afficher à celui qui l'appelle.
 */
@Singleton
class VoiceAnnouncer @Inject constructor(
    private val settings: VoiceSettings,
    @AndroidEngine private val android: VoiceEngineTts,
    @VoxtralEngine private val voxtral: VoiceEngineTts,
    @PiperEngine private val piper: VoiceEngineTts,
) {

    /**
     * Dit [text]. Voxtral d'abord si — et seulement si — l'utilisateur l'a choisi, a consenti à
     * l'envoi et a enregistré une clé ; sinon, ou en cas d'échec de la synthèse, la voix
     * Android.
     */
    suspend fun announce(text: String): SpokenBy {
        if (text.isBlank()) return SpokenBy.NONE
        preferredIssue = null
        val config = settings.config.value
        if (config.usesPiper()) {
            if (speakQuietly(piper, text)) return SpokenBy.PIPER
            preferredIssue = piper.lastIssue
        }
        if (config.canUseVoxtral()) {
            if (speakQuietly(voxtral, text)) return SpokenBy.VOXTRAL
            preferredIssue = voxtral.lastIssue
        }
        return if (speakQuietly(android, text)) SpokenBy.ANDROID else SpokenBy.NONE
    }

    /**
     * Annonce un repas à partir de ses glucides. Le texte est figé par
     * [MealAnnouncementText.forCarbs] : mêmes glucides, même phrase, donc même entrée de cache.
     */
    suspend fun announceMeal(carbsGrams: Double): SpokenBy =
        announce(MealAnnouncementText.forCarbs(carbsGrams))

    /**
     * Les moteurs promettent de ne pas lever ; on s'en assure quand même ici. Un moteur qui
     * lancerait une exception ne doit pas empêcher le repli — c'est précisément le moment où
     * il faut que la voix Android prenne la main.
     *
     * Ce qui est avalé n'est pas perdu : [lastSwallowed] le retient pour l'écran d'essai, sans
     * quoi un échec deviendrait un silence inexpliqué. Jamais de clé là-dedans, seulement le
     * type et le message de l'exception.
     */
    private suspend fun speakQuietly(engine: VoiceEngineTts, text: String): Boolean =
        try {
            engine.speak(text)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            lastSwallowed = "${error::class.simpleName} : ${error.message.orEmpty()}"
            false
        }

    /**
     * Pourquoi le moteur choisi (Piper ou Voxtral) n'a pas parlé lors de la dernière annonce ;
     * `null` s'il a parlé ou si la voix Android était le choix. Pour l'écran d'essai.
     */
    @Volatile
    var preferredIssue: String? = null
        private set

    /** Dernière exception avalée par [speakQuietly], pour l'écran d'essai. */
    @Volatile
    var lastSwallowed: String? = null
        private set
}
