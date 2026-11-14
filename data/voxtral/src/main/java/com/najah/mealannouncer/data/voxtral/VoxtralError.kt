package com.najah.mealannouncer.data.voxtral

/** Ce qui peut empêcher une synthèse Voxtral, du plus définitif au plus passager. */
enum class VoxtralErrorKind {
    /** Clé API absente : le moteur Voxtral ne peut pas être appelé. */
    NOT_CONFIGURED,

    /** Mistral refuse la clé (401/403). */
    UNAUTHORIZED,

    /** Quota ou cadence dépassés (429) : réessayer plus tard. */
    QUOTA,

    /** Aucune réponse dans le délai imparti. */
    TIMEOUT,

    /** Serveur injoignable : réseau coupé, DNS, TLS. */
    NETWORK,

    /** Mistral répond une erreur de son côté (5xx). */
    SERVER,

    /** Code HTTP inattendu. */
    UNEXPECTED,

    /** Réponse illisible (JSON invalide, audio manquant). */
    INVALID_RESPONSE,
    ;

    /**
     * Vrai si réessayer a un sens : une panne passagère, pas un défaut de configuration
     * ni un refus de clé. Le quota en fait partie — il se libère avec le temps.
     */
    val isRetryable: Boolean
        get() = this == TIMEOUT || this == NETWORK || this == SERVER || this == QUOTA
}

/** Échec de communication avec Voxtral, déjà classé. */
class VoxtralException(
    val kind: VoxtralErrorKind,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Message destiné à l'utilisateur, en français et sans jargon.
 *
 * Il vit ici, et non dans les ressources, pour que l'écran « Voix » et une éventuelle
 * bulle d'erreur disent exactement la même chose du même incident. Une annonce ne dépend
 * jamais de Voxtral pour aboutir : ces messages n'expliquent qu'un repli, pas un échec.
 */
val VoxtralErrorKind.userMessage: String
    get() = when (this) {
        VoxtralErrorKind.NOT_CONFIGURED ->
            "La clé Voxtral n'est pas enregistrée : la voix Android est utilisée."
        VoxtralErrorKind.UNAUTHORIZED ->
            "Mistral a refusé la clé Voxtral. Vérifiez-la dans les réglages."
        VoxtralErrorKind.QUOTA ->
            "Quota Voxtral atteint : la voix Android prend le relais."
        VoxtralErrorKind.TIMEOUT ->
            "Voxtral n'a pas répondu à temps : la voix Android prend le relais."
        VoxtralErrorKind.NETWORK ->
            "Voxtral injoignable : la voix Android prend le relais."
        VoxtralErrorKind.SERVER ->
            "Voxtral a renvoyé une erreur de son côté : la voix Android prend le relais."
        VoxtralErrorKind.UNEXPECTED ->
            "Réponse inattendue de Voxtral : la voix Android prend le relais."
        VoxtralErrorKind.INVALID_RESPONSE ->
            "Voxtral a répondu quelque chose d'illisible : la voix Android prend le relais."
    }
