package com.najah.mealannouncer.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Règle du bandeau d'accueil « une fonction de Miam-Miam est coupée ». */
class AccessibilityAlertTest {

    private val running = AccessibilityBindingState.BOUND_AND_RUNNING

    @Test
    fun pas_d_alerte_si_l_utilisateur_n_utilise_pas_l_insertion() {
        val state = SetupWizardUiState(camapsWanted = false, camapsBinding = AccessibilityBindingState.OFF)
        assertFalse(state.showAccessibilityAlert)
    }

    @Test
    fun alerte_si_insertion_voulue_et_service_retire_apres_mise_a_jour() {
        val state = SetupWizardUiState(camapsWanted = true, camapsBinding = AccessibilityBindingState.OFF)
        assertTrue(state.camapsBroken)
        assertTrue(state.showAccessibilityAlert)
    }

    @Test
    fun alerte_si_service_coche_mais_pas_relance() {
        val state = SetupWizardUiState(
            camapsWanted = true,
            camapsBinding = AccessibilityBindingState.UNBOUND_AFTER_UPDATE,
        )
        assertTrue(state.showAccessibilityAlert)
    }

    @Test
    fun pas_d_alerte_quand_tout_tourne() {
        val state = SetupWizardUiState(
            camapsWanted = true,
            glookoWanted = true,
            camapsBinding = running,
            glookoBinding = running,
        )
        assertFalse(state.showAccessibilityAlert)
    }

    @Test
    fun alerte_glooko_seule() {
        val state = SetupWizardUiState(
            camapsWanted = true,
            glookoWanted = true,
            camapsBinding = running,
            glookoBinding = AccessibilityBindingState.OFF,
        )
        assertFalse(state.camapsBroken)
        assertTrue(state.glookoBroken)
        assertTrue(state.showAccessibilityAlert)
    }
}
