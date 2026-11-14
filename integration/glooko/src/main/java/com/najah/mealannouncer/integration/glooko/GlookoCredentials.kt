package com.najah.mealannouncer.integration.glooko

import com.najah.mealannouncer.core.datastore.NightscoutSettings
import com.najah.mealannouncer.data.nightscout.NightscoutCredentials
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Qui a le droit d'écrire dans Nightscout.
 *
 * Le secret n'est lu qu'ici, au moment de l'envoi : ni l'import, ni le rapport, ni le journal
 * ne le voient passer. Un import qui ne peut pas s'authentifier s'arrête avant d'écrire.
 */
@Singleton
class GlookoCredentials @Inject constructor(
    private val settings: NightscoutSettings,
) {
    /** Vrai si Nightscout est activé par l'utilisateur. */
    fun isEnabled(): Boolean = settings.config.value.enabled

    /** Adresse et secret, ou `null` si la configuration est incomplète. */
    fun read(): NightscoutCredentials? {
        val config = settings.config.value
        if (!config.isComplete()) return null
        val secret = settings.readSecret()?.takeIf { it.isNotBlank() } ?: return null
        return NightscoutCredentials(config.baseUrl, config.credentialType, secret)
    }

    /** L'utilisateur a demandé une simulation : rien ne doit partir. */
    fun isSimulation(): Boolean = settings.config.value.simulation
}
