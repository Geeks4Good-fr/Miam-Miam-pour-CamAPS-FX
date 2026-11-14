package com.najah.mealannouncer.core.datastore

/** Façon dont Nightscout reconnaît l'application. */
enum class CredentialType {
    /**
     * L'`API_SECRET` du serveur. Nightscout attend son **SHA-1 hexadécimal** dans l'en-tête
     * `api-secret` : envoyer le secret en clair donne un 401 (vérifié sur le serveur d'essai).
     */
    API_SECRET,

    /** Un token d'accès créé dans Nightscout ; il part tel quel dans `api-secret`. */
    ACCESS_TOKEN,
}

/**
 * Réglages Nightscout, **sans le secret** : celui-ci ne transite que par [SecretStore].
 *
 * [simulation] permet d'essayer tout le parcours sans écrire chez personne : le client
 * répond comme si l'envoi avait réussi, mais rien ne quitte le téléphone.
 */
data class NightscoutConfig(
    val baseUrl: String = "",
    val credentialType: CredentialType = CredentialType.API_SECRET,
    val enabled: Boolean = false,
    val simulation: Boolean = false,
    val hasSecret: Boolean = false,
) {
    /** Vrai si l'adresse et le secret sont renseignés. */
    fun isComplete(): Boolean = baseUrl.isNotBlank() && hasSecret

    /** Vrai si un envoi réel doit être tenté (activé, complet, hors simulation). */
    fun canSend(): Boolean = enabled && isComplete() && !simulation
}
