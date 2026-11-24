package com.najah.mealannouncer.integration.camaps

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La porte **automatique** vers CamAPS, par le service d'accessibilité.
 *
 * Elle ouvre « Ajouter un repas », remplit les glucides, coche le type choisi par l'utilisateur, et — sur
 * confirmation vocale seulement — appuie sur « Continuer ». Elle s'arrête là : jamais un bolus,
 * jamais un écran de dosage. Tout écart par rapport à l'écran attendu rend un échec explicite et
 * laisse CamAPS tel quel (la décision est prise par [CamapsScreen], pure et testée).
 */
@Singleton
class AccessibilityCamapsGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : CamapsMealGateway {

    override suspend fun writeCarbs(carbs: Int): CamapsWriteResult =
        announceMeal(CamapsMealRequest(carbs = carbs, type = CamapsMealType.MEAL, confirm = false))

    override suspend fun announceMeal(request: CamapsMealRequest): CamapsWriteResult {
        if (request.carbs <= 0) {
            return CamapsWriteResult.Failed("Aucun glucides à annoncer dans CamAPS.")
        }
        val service = CamapsAccessibilityService.instance
            ?: return CamapsWriteResult.Failed(
                "Le service d'accessibilité CamAPS n'est pas activé dans les réglages d'Android.",
            )
        val packageName = CamapsPackages.installed(context.packageManager)
            ?: return CamapsWriteResult.Failed("CamAPS FX n'est pas installé sur ce téléphone.")

        launch(packageName)
        val opened = await(LAUNCH_TIMEOUT_MS) {
            service.root()?.packageName?.toString() == packageName
        }
        if (!opened) {
            return CamapsWriteResult.Failed("CamAPS FX ne s'est pas ouvert.")
        }
        val result = automate(service, request)
        // Un échec ne laisse jamais CamAPS au premier plan sur un menu ouvert : le 29/10/2026, un
        // tiroir resté ouvert à l'extinction de l'écran a précédé l'arrêt forcé de CamAPS par le
        // « tueur » du téléphone (44 min sans boucle). Retour à l'accueil d'Android : CamAPS reste
        // en arrière-plan comme d'habitude. Jamais quand la fenêtre « Confirmer » est ouverte, ni
        // quand les glucides sont saisis et qu'il ne reste qu'à terminer à la main.
        if (result is CamapsWriteResult.Failed && !result.message.startsWith(MANUAL_FINISH_PREFIX)) {
            leaveCamaps(service, packageName)
        }
        return result
    }

    private suspend fun automate(
        service: CamapsAccessibilityService,
        request: CamapsMealRequest,
    ): CamapsWriteResult {
        // Atteindre « Ajouter un repas ». L'entrée vit dans le tiroir de menu, fermé par défaut :
        // il faut l'ouvrir avant de pouvoir la toucher.
        if (!reachMealScreen(service)) {
            // Pour comprendre un échec sur l'appareil : les libellés visibles, jamais un chiffre.
            val labels = CamapsUi.snapshot(service).nodes.map { it.label() }
                .filter { it.isNotBlank() && it.none(Char::isDigit) }.distinct().take(30)
            android.util.Log.w(LOG_TAG, "« ${CamapsScreen.TITLE} » non atteint ; libellés visibles : $labels")
            return CamapsWriteResult.Failed("L'écran « ${CamapsScreen.TITLE} » n'a pas pu être atteint.")
        }

        val snapshot = CamapsUi.snapshot(service)
        return when (val decision = CamapsScreen.decide(snapshot, request.carbs, request.type, request.confirm)) {
            is CamapsDecision.Refuse -> CamapsWriteResult.Failed(decision.reason)
            is CamapsDecision.Fill -> apply(service, decision)
        }
    }

    /**
     * Quitte CamAPS par l'accueil d'Android, sauf si la fenêtre « Confirmer » est ouverte : elle
     * appartient à l'utilisateur. N'appuie sur rien dans CamAPS.
     */
    private fun leaveCamaps(service: CamapsAccessibilityService, packageName: String) {
        if (observe(service, packageName) == CamapsObservedState.DIALOG) return
        if (service.root()?.packageName?.toString() != packageName) return
        android.util.Log.i(LOG_TAG, "Échec de l'automate : retour à l'accueil d'Android")
        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
    }

    /** Exécute la décision : glucides, type, puis — si demandé — « Continuer ». */
    private suspend fun apply(
        service: CamapsAccessibilityService,
        fill: CamapsDecision.Fill,
    ): CamapsWriteResult {
        val carbs = fill.carbsText.toIntOrNull() ?: 0
        if (!CamapsUi.setFirstEditable(service, fill.carbsText)) {
            return CamapsWriteResult.Failed("Le champ des glucides n'a pas pu être rempli.")
        }
        delay(SETTLE_MS)
        // Plusieurs essais, l'écran relu à chaque fois : juste après la saisie des glucides, le
        // clavier et la remise en page de CamAPS peuvent encore cacher les types (échec réel du
        // 23/11/2026 à 18:36, une seule tentative à 0,6 s).
        val typeDeadline = System.currentTimeMillis() + TYPE_RETRY_MS
        var typed = CamapsUi.clickExact(service, fill.mealTypeLabel)
        while (!typed && System.currentTimeMillis() < typeDeadline) {
            delay(TYPE_POLL_MS)
            typed = CamapsUi.clickExact(service, fill.mealTypeLabel)
        }
        if (!typed) {
            // Pour comprendre un échec sur l'appareil : les libellés visibles, jamais un chiffre.
            val labels = CamapsUi.snapshot(service).nodes.map { it.label() }
                .filter { it.isNotBlank() && it.none(Char::isDigit) }.distinct().take(30)
            android.util.Log.w(LOG_TAG, "Type « ${fill.mealTypeLabel} » non coché ; libellés visibles : $labels")
            return CamapsWriteResult.Failed("Le type « ${fill.mealTypeLabel} » n'a pas pu être coché.")
        }
        if (fill.pressContinue) {
            delay(SETTLE_MS)
            if (!CamapsUi.clickContaining(service, CamapsScreen.CONTINUE_LABEL)) {
                // Le champ est rempli et le type coché : on ne valide pas en force, on le dit.
                return CamapsWriteResult.Failed(
                    "$MANUAL_FINISH_PREFIX « ${CamapsScreen.CONTINUE_LABEL} » n'a pas répondu. Terminez à la main.",
                )
            }
        }
        return CamapsWriteResult.Entered(carbs)
    }

    /**
     * Observe CamAPS après « Continuer », sans rien toucher, jusqu'à savoir si l'annonce a été
     * confirmée ou annulée. Chaque observation est journalisée (états seulement, aucun chiffre),
     * pour vérifier sur l'appareil que la lecture est juste.
     */
    override suspend fun awaitMealOutcome(timeoutMs: Long): CamapsMealOutcome {
        val service = CamapsAccessibilityService.instance ?: return CamapsMealOutcome.UNKNOWN
        val packageName = CamapsPackages.installed(context.packageManager) ?: return CamapsMealOutcome.UNKNOWN
        val tracker = CamapsOutcomeTracker()
        val deadline = System.currentTimeMillis() + timeoutMs
        var previous: CamapsObservedState? = null
        while (System.currentTimeMillis() < deadline) {
            val state = observe(service, packageName)
            if (state != previous) {
                android.util.Log.i(LOG_TAG, "CamAPS observé : $state")
                previous = state
            }
            tracker.observe(state)?.let { outcome ->
                android.util.Log.i(LOG_TAG, "Issue de l'annonce : $outcome")
                return outcome
            }
            delay(OBSERVE_MS)
        }
        android.util.Log.i(LOG_TAG, "Issue de l'annonce : TIMEOUT")
        return CamapsMealOutcome.TIMEOUT
    }

    private fun observe(service: CamapsAccessibilityService, packageName: String): CamapsObservedState {
        val root = service.root() ?: return CamapsObservedState.OTHER_APP
        if (root.packageName?.toString() != packageName) return CamapsObservedState.OTHER_APP
        val snapshot = CamapsUi.snapshot(service)
        val labels = snapshot.nodes.map { it.label() }
        val dialog = labels.any { CamapsScreen.sameLabel(it, CONFIRM_LABEL) } &&
            labels.any { CamapsScreen.sameLabel(it, CANCEL_LABEL) }
        return when {
            dialog -> CamapsObservedState.DIALOG
            onMealScreen(service) -> CamapsObservedState.FORM
            else -> CamapsObservedState.ELSEWHERE
        }
    }

    private fun onMealScreen(service: CamapsAccessibilityService): Boolean {
        val snapshot = CamapsUi.snapshot(service)
        val hasTitle = snapshot.nodes.any { CamapsScreen.containsLabel(it.label(), CamapsScreen.TITLE) }
        // Le menu latéral porte aussi « Ajouter un repas » : seul l'écran réel a EN PLUS un champ
        // éditable (le champ des glucides). C'est cette conjonction qui distingue l'écran du menu.
        return hasTitle && snapshot.nodes.any { it.editable }
    }

    /**
     * Atteint l'écran « Ajouter un repas ».
     *
     * Relevé sur l'appareil : au lancement, CamAPS montre son accueil, **tiroir de menu fermé**
     * (bouton « options ouvertes » = invite à ouvrir). « Ajouter un repas » n'est alors pas dans
     * l'arbre. On ouvre donc le tiroir, puis on touche l'entrée — en réessayant jusqu'à ce que
     * l'écran de saisie (titre + champ éditable) paraisse.
     */
    private suspend fun reachMealScreen(service: CamapsAccessibilityService): Boolean {
        val deadline = System.currentTimeMillis() + REACH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (onMealScreen(service)) return true
            // « Ajouter un repas » visible (tiroir ouvert) → on le touche ; sinon on ouvre le tiroir.
            if (!CamapsUi.clickContaining(service, CamapsScreen.TITLE)) {
                CamapsUi.clickContaining(service, DRAWER_OPEN_HINT)
            }
            delay(SETTLE_MS)
        }
        return onMealScreen(service)
    }

    private fun launch(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private suspend fun await(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(POLL_MS)
        }
        return condition()
    }

    private companion object {
        /** Début du message quand les glucides sont saisis : CamAPS reste alors à l'écran. */
        const val MANUAL_FINISH_PREFIX = "Glucides saisis, mais"

        const val LAUNCH_TIMEOUT_MS = 15_000L
        const val STEP_TIMEOUT_MS = 8_000L
        /** Atteindre l'écran de repas peut demander d'ouvrir le tiroir puis d'attendre l'écran. */
        const val REACH_TIMEOUT_MS = 12_000L
        const val SETTLE_MS = 600L

        /** Temps laissé pour cocher le type de repas après la saisie des glucides, et pas entre deux essais. */
        const val TYPE_RETRY_MS = 4_000L
        const val TYPE_POLL_MS = 400L
        const val POLL_MS = 250L

        /** Description du bouton de menu quand le tiroir est fermé (invite à l'ouvrir). */
        const val DRAWER_OPEN_HINT = "options ouvert"

        /** Les deux boutons de la boîte qui suit « Continuer » (relevé du 26/10). */
        const val CONFIRM_LABEL = "Confirmer"
        const val CANCEL_LABEL = "Annuler"

        const val OBSERVE_MS = 400L
        const val LOG_TAG = "MealAnnouncerCamaps"
    }
}

/** Lectures et gestes élémentaires sur l'arbre d'accessibilité de CamAPS. */
internal object CamapsUi {

    private const val MAX_DEPTH = 40
    private const val MAX_NODES = 2_000
    private const val POLL_MS = 200L

    /** L'écran courant, converti pour [CamapsScreen]. */
    fun snapshot(service: CamapsAccessibilityService): CamapsScreenSnapshot {
        val root = service.root() ?: return CamapsScreenSnapshot(emptyList())
        val out = ArrayList<CamapsNode>()
        collect(root, out, 0, intArrayOf(0))
        return CamapsScreenSnapshot(out)
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        out: MutableList<CamapsNode>,
        depth: Int,
        counter: IntArray,
    ) {
        if (depth > MAX_DEPTH || ++counter[0] > MAX_NODES) return
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
        val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val className = node.className?.toString()
        val editable = node.isEditable || className?.contains("EditText") == true
        if (text != null || desc != null || editable) {
            out += CamapsNode(
                text = text,
                contentDesc = desc,
                className = className,
                editable = editable,
                clickable = node.isClickable,
            )
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collect(child, out, depth + 1, counter)
        }
    }

    /** Touche le premier nœud dont un libellé **contient** [label] (en attendant qu'il paraisse). */
    suspend fun clickFirst(service: CamapsAccessibilityService, label: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val node = findContaining(service, label)
            if (node != null && click(node)) return true
            delay(POLL_MS)
        }
        return false
    }

    /** Touche le nœud dont le libellé **est exactement** [label] (apostrophes normalisées). */
    fun clickExact(service: CamapsAccessibilityService, label: String): Boolean {
        val node = findExact(service, label) ?: return false
        return click(node)
    }

    /** Touche le nœud dont le libellé **contient** [label]. */
    fun clickContaining(service: CamapsAccessibilityService, label: String): Boolean {
        val node = findContaining(service, label) ?: return false
        return click(node)
    }

    /** Écrit [value] dans le premier champ éditable trouvé. */
    fun setFirstEditable(service: CamapsAccessibilityService, value: String): Boolean {
        val root = service.root() ?: return false
        val field = firstEditable(root, 0, intArrayOf(0)) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findExact(service: CamapsAccessibilityService, label: String): AccessibilityNodeInfo? =
        search(service) { node -> CamapsScreen.sameLabel(labelOf(node), label) }

    private fun findContaining(service: CamapsAccessibilityService, label: String): AccessibilityNodeInfo? =
        search(service) { node -> CamapsScreen.containsLabel(labelOf(node), label) }

    private fun search(
        service: CamapsAccessibilityService,
        match: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val root = service.root() ?: return null
        return walk(root, 0, intArrayOf(0), match)
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        counter: IntArray,
        match: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH || ++counter[0] > MAX_NODES) return null
        if (labelOf(node).isNotBlank() && match(node)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            walk(child, depth + 1, counter, match)?.let { return it }
        }
        return null
    }

    private fun firstEditable(node: AccessibilityNodeInfo, depth: Int, counter: IntArray): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH || ++counter[0] > MAX_NODES) return null
        val className = node.className?.toString()
        if (node.isEditable || className?.contains("EditText") == true) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            firstEditable(child, depth + 1, counter)?.let { return it }
        }
        return null
    }

    /** Touche le nœud, ou son plus proche ancêtre cliquable. */
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
            depth++
        }
        return false
    }

    private fun labelOf(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        return listOfNotNull(text, desc).joinToString(" ").trim()
    }
}
