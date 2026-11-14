package com.najah.mealannouncer.integration.glookoexport

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Service d'accessibilité dédié à l'application Glooko.
 *
 * Il n'agit pas de lui-même : il ne fait que rendre l'arbre de l'écran courant lisible pour
 * l'automatisation, quand celle-ci le demande. Aucune surveillance continue, aucun écran
 * d'une autre application n'est touché — en particulier jamais CamAPS FX, dont une
 * confirmation automatisée pourrait décider d'une dose.
 */
class GlookoAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
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
         * Instance vivante du service, ou `null` s'il n'est pas activé.
         *
         * Le mutateur reste dans le module : seule la classe elle-même relie et délie
         * l'instance, le reste de l'application ne fait que lire.
         */
        @Volatile
        var instance: GlookoAccessibilityService? = null
            internal set

        /** Vrai si l'utilisateur a activé le service dans les réglages d'accessibilité. */
        val isRunning: Boolean get() = instance != null
    }
}
