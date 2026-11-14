package com.najah.mealannouncer.data.nightscout

/** Ce qui peut empêcher un envoi vers Nightscout, du plus définitif au plus passager. */
enum class NightscoutErrorKind {
    /** Adresse ou secret manquant. */
    NOT_CONFIGURED,

    /** Nightscout refuse l'identifiant (401/403). */
    UNAUTHORIZED,

    /** L'adresse ne correspond à aucune ressource (404) : URL ou déploiement à vérifier. */
    NOT_FOUND,

    /** Aucune réponse dans le délai imparti. */
    TIMEOUT,

    /** Serveur injoignable : réseau coupé, DNS, TLS. */
    NETWORK,

    /** Nightscout répond une erreur de son côté (5xx). */
    SERVER,

    /** Code HTTP inattendu. */
    UNEXPECTED,

    /** Réponse illisible (JSON invalide, champ manquant). */
    INVALID_RESPONSE,

    /**
     * L'API que ce serveur accepte ne sait pas écrire de profil.
     *
     * La v1 n'expose que `GET /api/v1/profile` : un serveur qui refuse la v3 aux clés
     * d'API (401) ne peut donc pas recevoir le profil. Ce n'est pas une panne — c'est une
     * limite du serveur, et le reste de l'import n'a aucune raison d'échouer pour elle.
     */
    PROFILE_UNSUPPORTED,
    ;

    /**
     * Vrai si réessayer a un sens : une panne passagère, pas une erreur de configuration
     * ni un refus d'identifiant.
     */
    val isRetryable: Boolean
        get() = this == TIMEOUT || this == NETWORK || this == SERVER
}

/** Échec de communication avec Nightscout, déjà classé. */
class NightscoutException(
    val kind: NightscoutErrorKind,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Message destiné à l'utilisateur, en français et sans jargon.
 *
 * Il est ici plutôt que dans les ressources parce que deux écrans différents (réglages et
 * confirmation) doivent dire exactement la même chose du même incident.
 */
val NightscoutErrorKind.userMessage: String
    get() = when (this) {
        NightscoutErrorKind.NOT_CONFIGURED ->
            "Nightscout n'est pas configuré : renseignez l'adresse et le secret dans les réglages."
        NightscoutErrorKind.UNAUTHORIZED ->
            "Nightscout a refusé l'identifiant. Vérifiez l'API_SECRET (ou le token)."
        NightscoutErrorKind.NOT_FOUND ->
            "Adresse introuvable sur ce serveur. Vérifiez l'URL Nightscout."
        NightscoutErrorKind.TIMEOUT ->
            "Nightscout n'a pas répondu dans le délai imparti."
        NightscoutErrorKind.NETWORK ->
            "Impossible de joindre Nightscout : vérifiez la connexion."
        NightscoutErrorKind.SERVER ->
            "Nightscout a renvoyé une erreur de son côté. Nouvel essai plus tard."
        NightscoutErrorKind.UNEXPECTED ->
            "Réponse inattendue de Nightscout."
        NightscoutErrorKind.INVALID_RESPONSE ->
            "Nightscout a répondu quelque chose d'illisible."
        NightscoutErrorKind.PROFILE_UNSUPPORTED ->
            "Ce serveur n'accepte le profil qu'avec un token d'accès : la clé d'API ne suffit pas."
    }
