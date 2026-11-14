package com.najah.mealannouncer.data.homeassistant

/**
 * Quand se reconnecter à Home Assistant après une coupure.
 *
 * Vite d'abord (un redémarrage de HA ou un changement de Wi-Fi se règle en quelques secondes),
 * puis de plus en plus espacé pour ne pas vider la batterie quand on est loin de la maison.
 * Un jeton refusé ou une adresse invalide n'est **jamais** réessayé : HA bannit l'adresse après
 * cinq échecs, et le réglage doit être corrigé à la main.
 */
object ReconnectPolicy {

    private val DELAYS_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
    const val MAX_DELAY_MS = 60_000L

    /** Délai avant la tentative numéro [attempt] (0 = première reconnexion). */
    fun delayMs(attempt: Int): Long =
        if (attempt < 0) DELAYS_MS[0] else DELAYS_MS.getOrElse(attempt) { MAX_DELAY_MS }

    /** Vrai si l'échec [error] laisse une chance à une nouvelle tentative. */
    fun shouldRetry(error: Throwable): Boolean =
        (error as? HomeAssistantException)?.kind?.isRetryable ?: true
}
