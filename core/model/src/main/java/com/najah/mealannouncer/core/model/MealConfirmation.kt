package com.najah.mealannouncer.core.model

import java.time.Instant

/** État de l'annonce dans CamAPS FX. */
enum class CamapsStatus {
    /** L'utilisateur n'a pas demandé d'annonce. */
    NOT_REQUESTED,

    /** L'automatisation est en cours. */
    PENDING,

    /** La valeur est saisie dans CamAPS et l'utilisateur a validé lui-même. */
    UI_AUTOMATION_DONE,

    /** L'automatisation a échoué ; le mode manuel prend le relais. */
    FAILED,

    /** Abandonnée par l'utilisateur. */
    ABORTED,
}

/** État de l'écriture dans Nightscout. */
enum class NightscoutStatus {
    /** Envoi non demandé (Nightscout désactivé). */
    NOT_REQUESTED,

    /** En file d'attente, pas encore envoyé. */
    PENDING,

    /** Traitement accepté par Nightscout. */
    SENT,

    /** Échec définitif après reprises. */
    FAILED,

    /** Un traitement identique semble déjà présent : envoi suspendu. */
    DUPLICATE_SUSPECT,
}

/** État de la préparation Glooko. */
enum class GlookoStatus {
    /** L'utilisateur n'a pas demandé d'entrée Glooko. */
    NOT_REQUIRED,

    /** Entrée en file « à saisir dans Glooko ». */
    QUEUED,

    /** Glooko a été ouvert pour une saisie manuelle. */
    OPENED_FOR_MANUAL_ENTRY,

    /** Préremplissage automatique en cours. */
    UI_AUTOMATION_PENDING,

    /** Préremplissage effectué ; l'utilisateur a enregistré lui-même. */
    UI_AUTOMATION_DONE,

    /** Échec de l'ouverture ou du préremplissage. */
    FAILED,
}

/**
 * Le repas tel qu'il a été confirmé par l'utilisateur, avant ou après diffusion.
 *
 * [manualTotalEdited] trace une correction manuelle du total : l'écran d'historique
 * doit pouvoir la distinguer d'un total calculé.
 */
data class MealConfirmation(
    val id: String,
    val totalCarbs: Carbs,
    val items: List<ResolvedMealItem>,
    val notes: String = "",
    /** Phrase dictée à l'origine, conservée pour l'historique et la reprise. */
    val rawTranscript: String = "",
    val createdAt: Instant = Instant.now(),
    val camapsAnnounced: Boolean = false,
    val nightscoutSaved: Boolean = false,
    val glookoStatus: GlookoStatus = GlookoStatus.NOT_REQUIRED,
    val manualTotalEdited: Boolean = false,
) {
    /** Vrai si le total autorise une annonce (strictement positif). */
    fun isAnnounceable(): Boolean = totalCarbs.grams > 0.0

    /** Note lisible : la note libre, sinon la liste des produits. */
    fun noteOrSummary(): String = notes.ifBlank {
        items.joinToString(" + ") { "${it.productName} ×${it.quantity}" }
    }
}
