package com.najah.mealannouncer.data.mistral

/**
 * Pourquoi un appel à Mistral a échoué.
 *
 * Les catégories sont celles qui changent quelque chose à l'écran : une clé refusée se
 * corrige dans les réglages, un quota se réessaie plus tard, une panne réseau se réessaie
 * tout de suite, une réponse illisible ne se réessaie pas.
 */
enum class MistralErrorKind {
    /** Aucune clé n'est enregistrée : rien n'a été tenté. */
    NOT_CONFIGURED,

    /** Clé refusée (401/403). */
    UNAUTHORIZED,

    /** Quota ou débit dépassé (429). */
    QUOTA,

    /** Erreur du serveur Mistral (5xx). */
    SERVER,

    /** Réseau absent, DNS en échec, connexion coupée. */
    NETWORK,

    /** Le serveur n'a pas répondu dans le délai imparti. */
    TIMEOUT,

    /** Réponse reçue mais inexploitable (JSON absent, contenu vide, valeur aberrante). */
    INVALID_RESPONSE,

    /** Réponse à laquelle on ne s'attendait pas (400, 404…). */
    UNEXPECTED,
}

/**
 * Échec d'un appel Mistral, déjà classé.
 *
 * Le message est destiné à l'écran : il ne contient jamais la clé d'API, et jamais de
 * donnée de santé au-delà de ce que l'utilisateur vient de dire.
 */
class MistralException(
    val kind: MistralErrorKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
