package com.najah.mealannouncer.integration.camaps

import com.najah.mealannouncer.integration.camaps.CamapsObservedState.DIALOG
import com.najah.mealannouncer.integration.camaps.CamapsObservedState.ELSEWHERE
import com.najah.mealannouncer.integration.camaps.CamapsObservedState.FORM
import com.najah.mealannouncer.integration.camaps.CamapsObservedState.OTHER_APP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Le sort d'une annonce laissée dans CamAPS : le Carnet Glooko n'est écrit que sur une
 * confirmation **vue**, jamais sur une annulation ni sur un doute.
 */
class CamapsOutcomeTrackerTest {

    private fun run(vararg states: CamapsObservedState): CamapsMealOutcome? {
        val tracker = CamapsOutcomeTracker(stableCount = 3)
        var outcome: CamapsMealOutcome? = null
        for (state in states) {
            outcome = tracker.observe(state) ?: continue
            break
        }
        return outcome
    }

    @Test
    fun `boite puis accueil vaut confirmation`() {
        assertEquals(CamapsMealOutcome.CONFIRMED, run(FORM, DIALOG, DIALOG, ELSEWHERE, ELSEWHERE, ELSEWHERE))
    }

    @Test
    fun `sans boite le retour a l'accueil vaut confirmation`() {
        // Petites quantités (1 g) : « Continuer » enregistre et revient à l'accueil directement.
        assertEquals(CamapsMealOutcome.CONFIRMED, run(FORM, ELSEWHERE, ELSEWHERE, ELSEWHERE))
    }

    @Test
    fun `boite puis formulaire stable vaut annulation`() {
        assertEquals(CamapsMealOutcome.CANCELLED, run(DIALOG, FORM, FORM, FORM))
    }

    @Test
    fun `un formulaire fugace apres Confirmer n'est pas une annulation`() {
        assertEquals(
            "Le formulaire entrevu une fois avant l'accueil ne doit pas faire conclure à une annulation",
            CamapsMealOutcome.CONFIRMED,
            run(DIALOG, FORM, ELSEWHERE, ELSEWHERE, ELSEWHERE),
        )
    }

    @Test
    fun `le formulaire avant toute boite n'est rien`() {
        assertNull(run(FORM, FORM, FORM, FORM, FORM))
    }

    @Test
    fun `une autre application au premier plan ne conclut rien`() {
        assertNull(run(DIALOG, OTHER_APP, OTHER_APP, OTHER_APP, OTHER_APP))
    }

    @Test
    fun `la boite qui reste ouverte ne conclut rien`() {
        assertNull(run(DIALOG, DIALOG, DIALOG, DIALOG, DIALOG))
    }
}
