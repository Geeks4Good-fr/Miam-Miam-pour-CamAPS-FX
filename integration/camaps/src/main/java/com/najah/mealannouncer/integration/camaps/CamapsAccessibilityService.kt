package com.najah.mealannouncer.integration.camaps

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Service d'accessibilité dédié à CamAPS FX.
 *
 * Il n'agit pas de lui-même : il ne fait qu'exposer l'arbre de l'écran courant, quand
 * l'automatisation le demande. Aucune surveillance continue. Il ne lit que CamAPS, et
 * l'automatisation s'arrête **avant** tout écran qui dose de l'insuline (voir
 * `docs/CAMAPS-REPAS.md`).
 *
 * Calque de `GlookoAccessibilityService` : même sobriété, même cycle de vie.
 */
class CamapsAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Android relie ce service au démarrage, ou quand CamAPS Guardian le remet après un arrêt
        // forcé : c'est le moment de relancer le reste de l'appli (veille, liaison maison).
        runCatching { onConnected?.invoke(applicationContext) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Racine de la fenêtre active, ou `null` si l'écran n'est pas lisible. */
    fun root(): AccessibilityNodeInfo? = rootInActiveWindow

    companion object {
        /**
         * Instance vivante du service, ou `null` s'il n'est pas activé dans les réglages
         * d'accessibilité d'Android. Seule la classe relie et délie l'instance.
         */
        @Volatile
        var instance: CamapsAccessibilityService? = null
            internal set

        /** Vrai si l'utilisateur a activé le service. */
        val isRunning: Boolean get() = instance != null

        /** Posé par l'application : ce qu'il faut relancer quand Android relie le service. */
        @Volatile
        var onConnected: ((android.content.Context) -> Unit)? = null
    }
}
