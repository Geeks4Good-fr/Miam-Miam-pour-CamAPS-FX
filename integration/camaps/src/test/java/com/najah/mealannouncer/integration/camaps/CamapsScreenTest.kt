package com.najah.mealannouncer.integration.camaps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La décision d'écran CamAPS : remplir le bon écran, refuser tout le reste.
 *
 * Ces règles protègent la ligne rouge : l'automate ne doit agir que sur l'écran « Ajouter un
 * repas » reconnu, jamais sur un écran de dosage, jamais quand une ancre manque.
 */
class CamapsScreenTest {

    private fun mealScreen(withContinue: Boolean = true, typographicApostrophe: Boolean = false): CamapsScreenSnapshot {
        val hypoLabel = if (typographicApostrophe) {
            "Traitement de l\u2019hypoglycémie"
        } else {
            "Traitement de l'hypoglycémie"
        }
        val nodes = mutableListOf(
            CamapsNode(text = "Ajouter un repas"),
            CamapsNode(text = "Quantité", className = "android.widget.EditText", editable = true),
            CamapsNode(text = "g de glucides"),
            CamapsNode(text = "Repas ou collation", clickable = true),
            CamapsNode(text = hypoLabel, clickable = true),
            CamapsNode(text = "Repas à absorption lente", clickable = true),
        )
        if (withContinue) nodes += CamapsNode(text = "Continuer", clickable = true)
        return CamapsScreenSnapshot(nodes)
    }

    @Test
    fun `bon ecran - on remplit, on coche le type, on continue`() {
        val decision = CamapsScreen.decide(mealScreen(), carbs = 40, type = CamapsMealType.MEAL, pressContinue = true)
        assertTrue(decision is CamapsDecision.Fill)
        decision as CamapsDecision.Fill
        assertEquals("40", decision.carbsText)
        assertEquals("Repas ou collation", decision.mealTypeLabel)
        assertTrue(decision.pressContinue)
    }

    @Test
    fun `chaque type vise son propre radio`() {
        val hypo = CamapsScreen.decide(mealScreen(), 15, CamapsMealType.HYPO, true)
        val slow = CamapsScreen.decide(mealScreen(), 60, CamapsMealType.SLOW, true)
        assertEquals("Traitement de l'hypoglycémie", (hypo as CamapsDecision.Fill).mealTypeLabel)
        assertEquals("Repas à absorption lente", (slow as CamapsDecision.Fill).mealTypeLabel)
    }

    @Test
    fun `apostrophe typographique de CamAPS FX reconnue pour le traitement de l'hypoglycemie`() {
        val hypo = CamapsScreen.decide(mealScreen(typographicApostrophe = true), 13, CamapsMealType.HYPO, true)
        assertTrue(hypo is CamapsDecision.Fill)
        assertEquals("13", (hypo as CamapsDecision.Fill).carbsText)
    }

    @Test
    fun `sans confirmation - on remplit mais on ne continue pas`() {
        val decision = CamapsScreen.decide(mealScreen(), 40, CamapsMealType.MEAL, pressContinue = false)
        assertTrue(decision is CamapsDecision.Fill)
        assertEquals(false, (decision as CamapsDecision.Fill).pressContinue)
    }

    @Test
    fun `ecran de bolus - refuse toujours`() {
        val bolus = CamapsScreenSnapshot(
            listOf(
                CamapsNode(text = "Calculateur de bolus"),
                CamapsNode(text = "Quantité", editable = true),
                CamapsNode(text = "Repas ou collation", clickable = true),
                CamapsNode(text = "Continuer", clickable = true),
            ),
        )
        assertTrue(CamapsScreen.decide(bolus, 40, CamapsMealType.MEAL, true) is CamapsDecision.Refuse)
    }

    @Test
    fun `ecran inconnu - refuse`() {
        val home = CamapsScreenSnapshot(listOf(CamapsNode(text = "Accueil"), CamapsNode(text = "Auto mode")))
        assertTrue(CamapsScreen.decide(home, 40, CamapsMealType.MEAL, true) is CamapsDecision.Refuse)
    }

    @Test
    fun `champ des glucides absent - refuse`() {
        val sansChamp = CamapsScreenSnapshot(
            listOf(
                CamapsNode(text = "Ajouter un repas"),
                CamapsNode(text = "Repas ou collation", clickable = true),
                CamapsNode(text = "Continuer", clickable = true),
            ),
        )
        assertTrue(CamapsScreen.decide(sansChamp, 40, CamapsMealType.MEAL, true) is CamapsDecision.Refuse)
    }

    @Test
    fun `continuer demande mais absent - refuse`() {
        val decision = CamapsScreen.decide(mealScreen(withContinue = false), 40, CamapsMealType.MEAL, pressContinue = true)
        assertTrue(decision is CamapsDecision.Refuse)
    }

    @Test
    fun `glucides nul ou negatif - refuse`() {
        assertTrue(CamapsScreen.decide(mealScreen(), 0, CamapsMealType.MEAL, true) is CamapsDecision.Refuse)
        assertTrue(CamapsScreen.decide(mealScreen(), -5, CamapsMealType.MEAL, true) is CamapsDecision.Refuse)
    }
}
