package com.najah.mealannouncer.integration.glookoexport

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/** Ce qu'est devenue une tentative d'écriture dans le Carnet Glooko. */
sealed interface GlookoCarnetResult {

    /** L'entrée a été enregistrée. */
    data object Saved : GlookoCarnetResult

    /** Rien n'a été écrit ; [message] est affichable tel quel. */
    data class Failed(val message: String) : GlookoCarnetResult
}

/**
 * Écrit une entrée de repas dans le Carnet Glooko, par le service d'accessibilité.
 *
 * Contrairement à l'export (`GlookoAutomation`), ici on **écrit** : écran « Ajouter des données »,
 * section « Repas », champs description et glucides, puis « Enregistrer ». C'est du **journal** —
 * aucune insuline, aucune conséquence médicale. Les champs sont repérés par leur `resource-id`
 * (Glooko porte des identifiants stables, relevés le 26/10/2026 ; voir `docs/GLOOKO-CARNET.md`).
 * Les champs **Insuline** et **Glycémie** ne sont jamais touchés.
 */
@Singleton
class GlookoCarnet @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile
    private var running = false

    /**
     * Écrit l'entrée, puis revient dans Meal Announcer.
     *
     * Glooko reste visible pendant la saisie : un masque posé par-dessus le faisait juger caché
     * par Android, qui ne livrait plus son écran au service (essai du 27/10) — l'utilisateur a choisi de
     * s'en passer.
     *
     * Chaque étape est journalisée (nom de l'étape et résultat, jamais la description ni les
     * glucides) : c'est ce qui permet de voir où la saisie bloque.
     */
    suspend fun addEntry(description: String, carbs: Int, moment: CarnetMoment): GlookoCarnetResult {
        val service = GlookoAccessibilityService.instance
            ?: return GlookoCarnetResult.Failed(
                "Le service d'accessibilité Glooko n'est pas activé dans les réglages d'Android.",
            )
        if (running) return GlookoCarnetResult.Failed("Une écriture Glooko est déjà en cours.")
        running = true
        try {
            val result = write(service, description, carbs, moment)
            Log.i(LOG_TAG, "Résultat du Carnet : $result")
            return result
        } finally {
            backToApp()
            running = false
        }
    }

    private suspend fun write(
        service: GlookoAccessibilityService,
        description: String,
        carbs: Int,
        moment: CarnetMoment,
    ): GlookoCarnetResult {
        val packageName = PACKAGES.firstOrNull { installed(it) }
            ?: return GlookoCarnetResult.Failed("L'application Glooko n'est pas installée.")

        launch(packageName)
        val opened = await(LAUNCH_TIMEOUT_MS) { service.root()?.packageName?.toString() == packageName }
        step("ouverture de Glooko", opened)
        if (!opened) return GlookoCarnetResult.Failed("Glooko ne s'est pas ouvert.")

        val reached = reachAddRecord(service)
        step("écran « Ajouter des données »", reached)
        if (!reached) {
            return GlookoCarnetResult.Failed("L'écran « Ajouter des données » n'a pas pu être atteint.")
        }

        // Déplier la section « Repas » seulement si ses champs ne sont pas déjà là : la
        // retoucher si elle est ouverte la refermerait.
        if (findById(service, ID_DESCRIPTION) == null) {
            val unfolded = clickById(service, ID_MEAL_HEADER) || clickText(service, "Repas")
            step("dépliage de la section Repas", unfolded)
            await(STEP_TIMEOUT_MS) { findById(service, ID_DESCRIPTION) != null }
        }

        // Description seule (décision de l'utilisateur, 27/10) : CamAPS envoie déjà les glucides à Glooko
        // par sa propre synchronisation (tuiles relevées à 12:44, 12:53, 13:03 alors que le Carnet
        // avait échoué) ; les écrire ici les compterait deux fois. Si un jour ils sont réactivés :
        // les glucides d'abord, avec leur suffixe « g », puis toucher la description.
        if (WRITE_CARBS) {
            val counted = focusAndSetText(service, ID_CARBS, carbs.toString(), keepSuffix = true)
            step("champ glucides", counted)
            if (!counted) return GlookoCarnetResult.Failed("Le champ glucides du Carnet est introuvable.")
            delay(SETTLE_MS)
        }

        val described = focusAndSetText(service, ID_DESCRIPTION, description)
        step("champ description", described)
        if (!described) return GlookoCarnetResult.Failed("Le champ description du Carnet est introuvable.")
        delay(SETTLE_MS)

        // Le moment est un confort : s'il est introuvable, on enregistre quand même.
        step("moment « ${moment.label} »", clickText(service, moment.label))
        delay(SETTLE_MS)

        val pressed = clickById(service, ID_SAVE) || clickText(service, "Enregistrer")
        step("bouton Enregistrer", pressed)
        if (!pressed) return GlookoCarnetResult.Failed("Le bouton « Enregistrer » du Carnet est introuvable.")

        // Un appui n'est pas un enregistrement : l'écran de saisie doit se refermer. S'il reste
        // ouvert (champ refusé, message d'erreur), on le dit plutôt que d'annoncer un succès.
        val closed = await(STEP_TIMEOUT_MS) { findById(service, ID_SAVE) == null }
        step("fermeture de l'écran de saisie", closed)
        if (!closed) {
            return GlookoCarnetResult.Failed("Glooko n'a pas validé l'entrée : vérifiez le Carnet à la main.")
        }
        // Glooko finit sa sauvegarde après la fermeture de l'écran : le quitter tout de suite le
        // fait planter (« AddRecordActivity not attached », essai du 27/10, 13h36). On attend son
        // retour à l'écran principal, puis un court temps de sécurité.
        val settled = await(POST_SAVE_TIMEOUT_MS) { findById(service, ID_ADD) != null }
        step("retour de Glooko à l'écran principal", settled)
        delay(POST_SAVE_SETTLE_MS)
        return GlookoCarnetResult.Saved
    }

    private fun step(name: String, ok: Boolean) {
        Log.i(LOG_TAG, "Carnet — $name : ${if (ok) "OK" else "ÉCHEC"}")
    }

    /** Ramène Meal Announcer au premier plan, là où l'utilisateur a commencé. */
    private fun backToApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
        )
        runCatching { context.startActivity(intent) }
    }

    /**
     * Ouvre « Ajouter des données ».
     *
     * Au lancement, Glooko passe par un écran de démarrage (splash) où le bouton n'existe pas
     * encore : on réessaie le clic en boucle jusqu'à ce que l'écran de saisie paraisse, plutôt
     * que de cliquer une seule fois au mauvais moment.
     */
    private suspend fun reachAddRecord(service: GlookoAccessibilityService): Boolean {
        val deadline = System.currentTimeMillis() + REACH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (onAddRecord(service)) return true
            // Le bouton n'existe que sur l'onglet « Suivi » ; l'export Glooko laisse l'appli sur
            // « Paramètres ». Sans bouton visible, on revient d'abord à « Suivi ».
            val added = clickById(service, ID_ADD) || clickText(service, "Ajouter des données")
            if (added) {
                // Un appui suffit : on laisse l'écran s'ouvrir plutôt que d'en empiler deux.
                if (await(STEP_TIMEOUT_MS) { onAddRecord(service) }) return true
            } else {
                val tab = clickById(service, ID_TAB_SUIVI)
                Log.i(LOG_TAG, "Carnet — bouton d'ajout absent (${screenSummary(service)}), onglet Suivi : $tab")
            }
            delay(SETTLE_MS)
        }
        Log.i(LOG_TAG, "Carnet — écran final : ${screenSummary(service)}")
        return onAddRecord(service)
    }

    /** Quelques identifiants de l'écran courant, pour le journal : jamais de contenu saisi. */
    private fun screenSummary(service: GlookoAccessibilityService): String {
        val root = service.root() ?: return "écran illisible"
        val ids = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH || ids.size >= 12) return
            node.viewIdResourceName?.substringAfterLast('/')?.let { if (it !in ids) ids += it }
            for (index in 0 until node.childCount) node.getChild(index)?.let { collect(it, depth + 1) }
        }
        collect(root, 0)
        return "${root.packageName} : ${ids.joinToString(",")}"
    }

    /**
     * Vrai sur l'écran de saisie, reconnu à ses seuls identifiants. Pas de repli sur le titre
     * « Ajouter des données » : c'est aussi le texte du **bouton** de l'écran principal, et ce
     * repli a fait croire l'écran atteint (essai du 27/10, 13h20).
     */
    private fun onAddRecord(service: GlookoAccessibilityService): Boolean =
        findById(service, ID_DESCRIPTION) != null ||
            findById(service, ID_MEAL_HEADER) != null ||
            findById(service, ID_SAVE) != null

    // ---- Gestes élémentaires --------------------------------------------------

    private fun clickById(service: GlookoAccessibilityService, id: String): Boolean =
        findById(service, id)?.let { click(it) } ?: false

    private fun clickText(service: GlookoAccessibilityService, text: String): Boolean =
        findBy(service) { labelOf(it).equals(text, ignoreCase = true) }?.let { click(it) } ?: false

    /**
     * Touche le champ (le sélectionne), puis y écrit [value].
     *
     * [keepSuffix] : les champs numériques de Glooko (`EditTextWithSuffix`) portent leur unité dans
     * le texte même (« g »). Écrire « 7 » seul efface ce suffixe, et Glooko plante en replaçant le
     * curseur avant lui (position −1 — essai du 27/10, 13h29). On écrit donc « 7 g ».
     */
    private suspend fun focusAndSetText(
        service: GlookoAccessibilityService,
        id: String,
        value: String,
        keepSuffix: Boolean = false,
    ): Boolean {
        val field = findById(service, id) ?: return false
        field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        delay(FOCUS_MS)
        val text = if (keepSuffix) value + glookoSuffixOf(findById(service, id)?.text?.toString()) else value
        return setTextById(service, id, text)
    }

    private fun setTextById(service: GlookoAccessibilityService, id: String, value: String): Boolean {
        val node = findById(service, id) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findById(service: GlookoAccessibilityService, id: String): AccessibilityNodeInfo? =
        findBy(service) { it.viewIdResourceName?.substringAfterLast('/') == id }

    private fun findBy(
        service: GlookoAccessibilityService,
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
        if (match(node)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            walk(child, depth + 1, counter, match)?.let { return it }
        }
        return null
    }

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

    private fun labelOf(node: AccessibilityNodeInfo): String =
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .joinToString(" ").trim()

    private fun installed(packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (error: android.content.pm.PackageManager.NameNotFoundException) {
        false
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
        val PACKAGES = listOf("com.diabnext.diabnext", "com.glooko.app")

        const val LOG_TAG = "MealAnnouncerCarnet"

        const val ID_ADD = "btnAdd"
        const val ID_MEAL_HEADER = "btnOpenCloseMeal"
        const val ID_DESCRIPTION = "editMealDescription"
        const val ID_CARBS = "editCarbs"
        const val ID_SAVE = "btnSave"
        /** Onglet « Suivi » de la barre du bas (relevé du 27/10). */
        const val ID_TAB_SUIVI = "menuDashboard"
        /** Glucides écrits dans le Carnet : non, ils viennent déjà de CamAPS (voir [write]). */
        const val WRITE_CARBS = false
        const val POST_SAVE_TIMEOUT_MS = 8_000L
        const val POST_SAVE_SETTLE_MS = 1_500L
        const val FOCUS_MS = 300L

        const val LAUNCH_TIMEOUT_MS = 15_000L
        const val STEP_TIMEOUT_MS = 8_000L
        /** Atteindre l'écran de saisie peut demander de dépasser le splash puis d'attendre. */
        const val REACH_TIMEOUT_MS = 12_000L
        const val SETTLE_MS = 600L
        const val POLL_MS = 250L
        const val MAX_DEPTH = 40
        const val MAX_NODES = 2_000
    }
}

/** Suffixe du champ glucides de Glooko, tel que relevé le 27/10 (« g », espace compris). */
private const val CARBS_SUFFIX = " g"

/**
 * L'unité collée au nombre dans un champ numérique de Glooko : « g » dans « 12 g » comme dans
 * « g » seul (champ vide). À défaut, « g » : écrire un nombre sans son suffixe fait planter Glooko.
 */
internal fun glookoSuffixOf(current: String?): String =
    current.orEmpty().dropWhile { it.isDigit() || it == ',' || it == '.' }.ifEmpty { CARBS_SUFFIX }
