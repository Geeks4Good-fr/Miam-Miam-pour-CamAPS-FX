package com.najah.mealannouncer.data.homeassistant

/** Ce qui peut couper le lien avec Home Assistant, du plus définitif au plus passager. */
enum class HomeAssistantErrorKind {
    /** Adresse ou jeton absents : le canal maison ne peut pas démarrer. */
    NOT_CONFIGURED,

    /** Home Assistant refuse le jeton (`auth_invalid`). */
    UNAUTHORIZED,

    /** Aucune réponse dans le délai imparti. */
    TIMEOUT,

    /** Serveur injoignable ou connexion tombée : Wi-Fi, Tailscale, redémarrage de HA. */
    NETWORK,

    /** Home Assistant a répondu, mais refuse l'appel (service inconnu, droits, enceinte occupée). */
    SERVICE,

    /** Message illisible ou inattendu. */
    INVALID_RESPONSE,
    ;

    /**
     * Vrai si se reconnecter a un sens : une panne passagère, pas un défaut de configuration
     * ni un refus de jeton — réessayer un jeton refusé finirait par bannir l'adresse du téléphone
     * (HA bannit après 5 échecs).
     */
    val isRetryable: Boolean
        get() = this == TIMEOUT || this == NETWORK
}

/** Échec de communication avec Home Assistant, déjà classé. */
class HomeAssistantException(
    val kind: HomeAssistantErrorKind,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Message destiné à l'utilisateur, en français et sans jargon (même texte partout). */
val HomeAssistantErrorKind.userMessage: String
    get() = when (this) {
        HomeAssistantErrorKind.NOT_CONFIGURED ->
            "L'adresse ou le jeton de Home Assistant ne sont pas enregistrés."
        HomeAssistantErrorKind.UNAUTHORIZED ->
            "Home Assistant a refusé le jeton. Vérifiez-le dans les réglages."
        HomeAssistantErrorKind.TIMEOUT ->
            "Home Assistant n'a pas répondu à temps."
        HomeAssistantErrorKind.NETWORK ->
            "Home Assistant est injoignable (réseau ou Tailscale)."
        HomeAssistantErrorKind.SERVICE ->
            "Home Assistant a refusé la demande."
        HomeAssistantErrorKind.INVALID_RESPONSE ->
            "Home Assistant a répondu quelque chose d'illisible."
    }
