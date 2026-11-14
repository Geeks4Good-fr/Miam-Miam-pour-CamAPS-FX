package com.najah.mealannouncer.integration.glookoexport

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Où se trouvent les commandes dans l'application Glooko.
 *
 * Chemin relevé sur l'appareil le 24/10/2026 (Glooko XT 3.19.0, `com.diabnext.diabnext`,
 * écran 720×1612, interface en français) :
 *
 * 1. barre du bas « Paramètres » ;
 * 2. bandeau du haut « Compte » ;
 * 3. carte « Export de données » — elle est **sous la ligne de flottaison**, il faut faire
 *    défiler ;
 * 4. écran « Export de données » : format CSV, intervalle de dates, puis « EXPORT » ;
 * 5. l'application extrait les données sur son serveur — cela peut prendre une minute — puis
 *    ouvre la **feuille de partage d'Android** avec `collected_data…csv`. Le fichier n'est
 *    jamais écrit dans « Téléchargements » : il n'existe que par ce partage, et c'est notre
 *    activité `GlookoShareActivity` qui le reçoit.
 *
 * Le nom du paquet ne contient pas « glooko » (il n'apparaît que dans les classes, sous
 * `glooko.xt.*`) : c'est `com.diabnext.diabnext`, et c'est la première chose à essayer.
 */
data class GlookoAutomationSpec(
    /** Paquets de l'application Glooko ; le premier installé gagne. */
    val packages: List<String> = listOf("com.diabnext.diabnext", "com.glooko.app"),
    /** Délai d'attente de l'ouverture de l'application. */
    val launchTimeoutMs: Long = 15_000,
    /** Délai d'attente d'un libellé à l'écran. */
    val stepTimeoutMs: Long = 8_000,
    /** Onglet de la barre du bas. */
    val menuLabels: List<String> = listOf("Paramètres"),
    /** Bandeau du haut de l'écran des réglages. */
    val sectionLabels: List<String> = listOf("Compte"),
    /** Carte qui ouvre l'écran d'export (sous la ligne de flottaison). */
    val exportCardLabels: List<String> = listOf("Export de données"),
    /** Bouton qui déclenche l'extraction, sur l'écran d'export. */
    val exportLabels: List<String> = listOf("EXPORT", "Export"),
    /** Choix du format, quand il n'est pas déjà CSV. */
    val csvLabels: List<String> = listOf("CSV"),
    /**
     * Ligne « Intervalle de date » : elle ouvre une boîte de dialogue qui propose « 7 jours »
     * et « 15 jours ». Tant qu'on ne la valide pas, l'écran garde des dates d'attente (2019).
     */
    val rangeLabels: List<String> = listOf("7 jours", "15 jours", "Intervalle de date"),
    /** Bouton qui valide la boîte de dialogue de l'intervalle. */
    val rangeConfirmLabels: List<String> = listOf("OK", "Valider"),
    /** Attente de l'extraction côté serveur avant l'ouverture de la feuille de partage. */
    val extractionTimeoutMs: Long = 90_000,
    /** Notre propre nom dans la feuille de partage ; vide = le nom de l'application. */
    val shareLabels: List<String> = emptyList(),
)

/** Ce qui a empêché l'automatisation d'aboutir. */
enum class GlookoAutomationFailure {
    SERVICE_OFF,
    APP_NOT_FOUND,
    STEP_NOT_FOUND,
    ALREADY_RUNNING,

    /**
     * L'écran d'export propose un intervalle de dates qui ne contient aucune donnée récente.
     *
     * Constaté sur l'appareil : les deux champs affichent des dates de 2019 tant que
     * l'utilisateur n'a pas choisi d'intervalle. Exporter cet intervalle produirait un fichier
     * vide, que l'import prendrait pour un succès à zéro document.
     */
    DATE_RANGE,
}

/** Résultat d'une tentative d'export piloté. */
sealed interface GlookoAutomationResult {

    /** L'export a été déclenché : les étapes touchées sont listées, sans aucune donnée de santé. */
    data class Triggered(val steps: List<String>) : GlookoAutomationResult

    data class Failed(
        val failure: GlookoAutomationFailure,
        val label: String? = null,
    ) : GlookoAutomationResult {
        val userMessage: String
            get() = when (failure) {
                GlookoAutomationFailure.SERVICE_OFF ->
                    "Le service d'accessibilité « Export Glooko » n'est pas activé dans les réglages d'accessibilité d'Android."
                GlookoAutomationFailure.APP_NOT_FOUND ->
                    "L'application Glooko n'est pas installée sur ce téléphone."
                GlookoAutomationFailure.STEP_NOT_FOUND ->
                    "Écran Glooko inattendu : « ${label ?: "?"} » est introuvable. Lancez la cartographie pour relever les libellés."
                GlookoAutomationFailure.ALREADY_RUNNING ->
                    "Un export Glooko est déjà en cours."
                GlookoAutomationFailure.DATE_RANGE ->
                    "Glooko propose l'intervalle ${label ?: "?"}, qui ne contient aucune donnée récente. " +
                        "Rien n'a été exporté : ouvrez Glooko, Paramètres → Compte → Export de données, " +
                        "choisissez « 7 jours », puis relancez la synchronisation."
            }
    }
}

/**
 * Pilote l'export Glooko par l'accessibilité.
 *
 * La séquence suit le chemin relevé sur l'appareil : onglet « Paramètres », bandeau
 * « Compte », carte « Export de données » (atteinte en faisant défiler), bouton « EXPORT »,
 * puis notre propre nom dans la feuille de partage d'Android. Rien n'est saisi à la main,
 * aucune date n'est inventée — l'application applique son propre intervalle — et tout libellé
 * manquant arrête la séquence au lieu d'être contourné.
 */
@Singleton
class GlookoAutomation @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile
    private var running = false

    suspend fun triggerExport(spec: GlookoAutomationSpec = GlookoAutomationSpec()): GlookoAutomationResult {
        val service = GlookoAccessibilityService.instance
            ?: return GlookoAutomationResult.Failed(GlookoAutomationFailure.SERVICE_OFF)
        if (running) return GlookoAutomationResult.Failed(GlookoAutomationFailure.ALREADY_RUNNING)
        running = true
        try {
            val packageName = spec.packages.firstOrNull { installed(it) }
                ?: return GlookoAutomationResult.Failed(GlookoAutomationFailure.APP_NOT_FOUND)

            val steps = ArrayList<String>()
            launch(packageName, spec)

            val opened = await(spec.launchTimeoutMs) {
                service.root()?.packageName?.toString() == packageName
            }
            if (!opened) {
                return GlookoAutomationResult.Failed(GlookoAutomationFailure.STEP_NOT_FOUND, packageName)
            }
            steps += "ouverture"

            // Libellés qui prouvent qu'un écran est atteint. Ils servent à constater qu'un pas
            // est déjà fait au lieu de le manquer : Glooko se rouvre là où on l'a laissé, et un
            // onglet déjà sélectionné n'accepte plus le clic. Relevé sur l'appareil : relancer
            // depuis « Paramètres → Compte » faisait échouer le pas 1, l'onglet étant déjà actif.
            //
            // La correspondance se fait par « contient » : « Export » désignerait aussi la carte
            // « Export de données ». Seul « Intervalle de date » est propre à l'écran d'export.
            val exportScreen = spec.rangeLabels
            val accountScreen = spec.exportCardLabels + exportScreen
            val settingsScreen = spec.sectionLabels + accountScreen

            // Après un partage, Glooko reste sur l'écran d'export : au passage suivant il n'y a
            // rien à ouvrir, et chercher les onglets coûterait vingt-quatre secondes pour rien.
            if (GlookoUi.waitFor(service, exportScreen, SHORT_TIMEOUT_MS) != null) {
                steps += "écran d'export déjà à l'écran"
            } else {
                // 1. Onglet « Paramètres » de la barre du bas.
                val menu = GlookoUi.clickAny(service, spec.menuLabels, spec.stepTimeoutMs)
                if (menu != null) {
                    steps += menu
                    delay(SETTLE_MS)
                } else if (alreadyAt(service, settingsScreen, spec.stepTimeoutMs)) {
                    steps += "réglages déjà à l'écran"
                } else {
                    return GlookoAutomationResult.Failed(
                        GlookoAutomationFailure.STEP_NOT_FOUND,
                        spec.menuLabels.first(),
                    )
                }

                // 2. Bandeau « Compte » de l'écran des réglages.
                val section = GlookoUi.clickAny(service, spec.sectionLabels, spec.stepTimeoutMs)
                if (section != null) {
                    steps += section
                    delay(SETTLE_MS)
                } else if (alreadyAt(service, accountScreen, spec.stepTimeoutMs, scrolling = true)) {
                    steps += "compte déjà à l'écran"
                } else {
                    return GlookoAutomationResult.Failed(
                        GlookoAutomationFailure.STEP_NOT_FOUND,
                        spec.sectionLabels.first(),
                    )
                }

                // 3. Carte « Export de données », qui est sous la ligne de flottaison.
                val card = GlookoUi.clickScrolling(service, spec.exportCardLabels, spec.stepTimeoutMs)
                if (card != null) {
                    steps += card
                    delay(SETTLE_MS)
                } else if (alreadyAt(service, exportScreen, spec.stepTimeoutMs)) {
                    steps += "écran d'export déjà à l'écran"
                } else {
                    return GlookoAutomationResult.Failed(
                        GlookoAutomationFailure.STEP_NOT_FOUND,
                        spec.exportCardLabels.first(),
                    )
                }
            }

            // 4. Garde-fou : sans intervalle récent, l'export serait vide et l'import le
            //    prendrait pour un succès à zéro document. On s'arrête avant de toucher EXPORT.
            //
            //    Relevé sur l'appareil (Glooko 3.19.0) : l'écran garde, jusqu'à ce qu'on valide
            //    la sélection, les dates de sa première installation — `2019/08/31` → `2019/09/15`
            //    — alors même que la ligne affiche déjà « 7 jours ». On ne touche donc à la
            //    sélection que si la plage lue n'est pas utilisable : au régime normal (7 jours
            //    déjà validé) aucun geste de plus, et un intervalle d'attente reste refusé.
            // Le format est déjà « CSV » au régime normal : toucher la ligne ouvrirait un choix
            // dont on n'a pas besoin — et qu'il faudrait ensuite refermer.
            if (GlookoUi.find(service, spec.csvLabels) == null) {
                GlookoUi.clickAny(service, spec.csvLabels, SHORT_TIMEOUT_MS)?.let { steps += it }
            }
            var range = GlookoUi.dateRange(service)
            if (range == null || rangeProblem(range) != null) {
                // La sélection s'ouvre sur « 7 jours » ; on la valide telle quelle, sans jamais
                // saisir de date à la main. Mesuré : l'écran d'export répond « non cliquable »
                // les premières secondes, d'où le délai d'étape et non le délai court.
                if (GlookoUi.clickAny(service, spec.rangeLabels, spec.stepTimeoutMs) != null) {
                    val confirm = GlookoUi.clickAny(service, spec.rangeConfirmLabels, spec.stepTimeoutMs)
                        ?: return GlookoAutomationResult.Failed(
                            GlookoAutomationFailure.STEP_NOT_FOUND,
                            spec.rangeConfirmLabels.first(),
                        )
                    steps += confirm
                    delay(SETTLE_MS)
                    range = GlookoUi.dateRange(service)
                }
            }

            val usable = range ?: return GlookoAutomationResult.Failed(
                GlookoAutomationFailure.DATE_RANGE,
                "illisible (aucune date n'a pu être lue)",
            )
            val problem = rangeProblem(usable)
            if (problem != null) {
                return GlookoAutomationResult.Failed(GlookoAutomationFailure.DATE_RANGE, problem)
            }
            steps += "intervalle ${dateText.format(usable.first)} → ${dateText.format(usable.second)}"

            // 5. EXPORT : l'application extrait les données sur son serveur, puis ouvre la
            //    feuille de partage d'Android, où l'on touche notre propre nom.
            val exportLabel = GlookoUi.clickAny(service, spec.exportLabels, spec.stepTimeoutMs)
                ?: return GlookoAutomationResult.Failed(
                    GlookoAutomationFailure.STEP_NOT_FOUND,
                    spec.exportLabels.first(),
                )
            steps += exportLabel

            // La feuille de partage classe les applications : la nôtre, peu servie, est loin
            // dans la grille, qui défile. Sans défilement, elle resterait hors de l'arbre et
            // l'attente échouerait alors qu'elle est bien là.
            val targets = shareLabels(spec)
            val shareLabel = GlookoUi.clickScrolling(service, targets, spec.extractionTimeoutMs)
                ?: return GlookoAutomationResult.Failed(
                    GlookoAutomationFailure.STEP_NOT_FOUND,
                    targets.first(),
                )
            steps += shareLabel

            return GlookoAutomationResult.Triggered(steps)
        } finally {
            running = false
        }
    }

    /** Arbre de l'écran courant, mis en forme : sert à relever les libellés sur l'appareil. */
    suspend fun dump(spec: GlookoAutomationSpec = GlookoAutomationSpec()): String {
        val service = GlookoAccessibilityService.instance
            ?: return GlookoAutomationResult.Failed(GlookoAutomationFailure.SERVICE_OFF).userMessage
        val packageName = spec.packages.firstOrNull { installed(it) }
        if (packageName != null) launch(packageName, spec)
        await(spec.launchTimeoutMs) { service.root()?.packageName?.toString() == packageName }
        return GlookoUi.dump(service)
    }

    /** Mise en forme française des dates, pour les messages rendus à l'utilisateur. */
    private val dateText = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)

    /**
     * Ce qui cloche dans l'intervalle proposé par Glooko, ou null s'il est utilisable.
     *
     * L'export doit couvrir les dernières 24 heures. Une borne haute vieille de plus de deux
     * jours signifie que personne n'a choisi d'intervalle : l'application garde alors les dates
     * de sa première installation (2019 sur cet appareil) et l'export ne contiendrait rien.
     */
    private fun rangeProblem(range: Pair<LocalDate, LocalDate>): String? {
        val (from, to) = range
        val oldestAccepted = LocalDate.now().minusDays(MAX_RANGE_AGE_DAYS)
        val looksWrong = from > to || to < oldestAccepted
        return if (looksWrong) "${dateText.format(from)} → ${dateText.format(to)}" else null
    }

    /** Notre nom dans la feuille de partage : le libellé de l'application, ou celui imposé. */
    private fun shareLabels(spec: GlookoAutomationSpec): List<String> =
        spec.shareLabels.ifEmpty { listOf(appLabel()) }

    /** Libellé de cette application, tel qu'Android l'affiche dans la feuille de partage. */
    private fun appLabel(): String = try {
        context.packageManager.getApplicationLabel(context.applicationInfo).toString()
    } catch (error: RuntimeException) {
        "Miam-Miam pour CamAPS FX"
    }

    private fun installed(packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (error: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    private fun launch(packageName: String, spec: GlookoAutomationSpec) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * L'un de ces libellés est-il déjà à l'écran ?
     *
     * Sert à reconnaître un écran atteint au lieu d'exiger un clic impossible : un onglet déjà
     * sélectionné n'est plus cliquable, et un export interrompu laisse Glooko sur l'écran
     * d'export, sans barre du bas.
     */
    private suspend fun alreadyAt(
        service: GlookoAccessibilityService,
        labels: List<String>,
        timeoutMs: Long,
        scrolling: Boolean = false,
    ): Boolean {
        val found = if (scrolling) {
            GlookoUi.findScrolling(service, labels, timeoutMs)
        } else {
            GlookoUi.waitFor(service, labels, timeoutMs)
        }
        return found != null
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
        const val POLL_MS = 250L
        const val SETTLE_MS = 1_200L
        const val SHORT_TIMEOUT_MS = 1_200L

        /** Âge maximal, en jours, de la borne haute de l'intervalle exporté. */
        const val MAX_RANGE_AGE_DAYS = 2L
    }
}

/** Lectures et gestes élémentaires sur l'arbre d'accessibilité. */
internal object GlookoUi {

    private const val MAX_DEPTH = 40
    private const val MAX_NODES = 2_000

    /** Au-delà, on ne garde pas plus de candidats par recherche. */
    private const val MAX_MATCHES = 32

    /**
     * Touche le premier nœud correspondant à l'un des libellés, et rend le libellé touché.
     *
     * Un seul délai pour toute la liste : chercher cinq libellés l'un après l'autre ferait
     * attendre huit secondes par libellé absent.
     */
    suspend fun clickAny(
        service: GlookoAccessibilityService,
        labels: List<String>,
        timeoutMs: Long,
    ): String? = clickMatching(service, labels, timeoutMs, scroll = false)

    /**
     * Touche un libellé, en faisant défiler l'écran tant qu'il reste caché.
     *
     * La carte « Export de données » est sous la ligne de flottaison : sans défilement, elle
     * n'existe pas dans l'arbre d'accessibilité et la séquence échouerait à tort.
     */
    suspend fun clickScrolling(
        service: GlookoAccessibilityService,
        labels: List<String>,
        timeoutMs: Long,
    ): String? = clickMatching(service, labels, timeoutMs, scroll = true)

    /**
     * Touche le premier candidat qui accepte le clic.
     *
     * Tous les nœuds qui portent un libellé sont essayés, et non le premier trouvé : sur
     * l'écran d'export, le titre « Export de données » contient « Export » et arrive avant le
     * bouton « EXPORT » dans l'arbre. S'en tenir au premier venu ferait viser un titre inerte
     * et échouer le pas. Les libellés exacts passent avant les correspondances partielles.
     */
    private suspend fun clickMatching(
        service: GlookoAccessibilityService,
        labels: List<String>,
        timeoutMs: Long,
        scroll: Boolean,
    ): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = service.root()
            if (root != null) {
                val nodes = ArrayList<AccessibilityNodeInfo>()
                collectMatches(root, labels, 0, intArrayOf(0), nodes)
                val ordered = nodes.sortedBy { candidate ->
                    if (isExact(candidate, labels)) 0 else 1
                }
                for (node in ordered) {
                    val label = matchLabel(node, labels) ?: continue
                    if (click(node)) return label
                }
            }
            if (scroll) scrollForward(service)
            delay(POLL_MS)
        }
        return null
    }

    /** Le nœud porte-t-il exactement l'un des libellés (et non seulement un extrait) ? */
    private fun isExact(node: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val text = node.text?.toString()?.trim()
        val description = node.contentDescription?.toString()?.trim()
        return labels.any { label ->
            text.equals(label, ignoreCase = true) || description.equals(label, ignoreCase = true)
        }
    }

    /** Tous les nœuds qui portent l'un des libellés, dans l'ordre de l'arbre. */
    private fun collectMatches(
        node: AccessibilityNodeInfo,
        labels: List<String>,
        depth: Int,
        counter: IntArray,
        out: MutableList<AccessibilityNodeInfo>,
    ) {
        if (depth > MAX_DEPTH || out.size >= MAX_MATCHES) return
        if (++counter[0] > MAX_NODES) return
        if (matches(node, labels)) out += node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectMatches(child, labels, depth + 1, counter, out)
        }
    }

    /** Attend qu'un libellé apparaisse, sans rien toucher. */
    suspend fun waitFor(
        service: GlookoAccessibilityService,
        labels: List<String>,
        timeoutMs: Long,
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            find(service, labels)?.let { return it }
            delay(POLL_MS)
        }
        return find(service, labels)
    }

    /** Cherche un libellé en faisant défiler l'écran, sans rien toucher. */
    suspend fun findScrolling(
        service: GlookoAccessibilityService,
        labels: List<String>,
        timeoutMs: Long,
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            find(service, labels)?.let { return it }
            scrollForward(service)
            delay(POLL_MS)
        }
        return find(service, labels)
    }

    /** Fait défiler le premier conteneur défilable de l'écran. */
    fun scrollForward(service: GlookoAccessibilityService): Boolean {
        val root = service.root() ?: return false
        val target = firstScrollable(root, 0) ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun firstScrollable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        if (node.isScrollable) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            firstScrollable(child, depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * Les deux dates affichées sur l'écran d'export, dans l'ordre de lecture (« de », puis « à »).
     *
     * Relevé sur l'appareil le 24/10/2026, Glooko 3.19.0 : l'application change de format selon
     * l'état de l'intervalle.
     *
     * - avant validation de la sélection, dates d'attente en `aaaa/MM/jj` : `2019/08/31` →
     *   `2019/09/15` (celles de la première installation, jamais rafraîchies) ;
     * - après validation, dates réelles en `jj/MM/aaaa` : `17/10/2026` → `24/10/2026`.
     *
     * Les deux formats sont donc lus ; rendre l'un pour l'autre ferait échouer le garde-fou sur
     * un intervalle pourtant correct, ou l'inverse.
     *
     * Rend null si l'écran n'en montre pas deux.
     */
    fun dateRange(service: GlookoAccessibilityService): Pair<LocalDate, LocalDate>? {
        val root = service.root() ?: return null
        val dates = ArrayList<LocalDate>(2)
        collectDates(root, dates, 0)
        if (dates.size < 2) return null
        return dates[0] to dates[1]
    }

    private fun collectDates(node: AccessibilityNodeInfo, out: MutableList<LocalDate>, depth: Int) {
        if (depth > MAX_DEPTH || out.size >= 2) return
        val text = node.text?.toString().orEmpty()
        DATE_PATTERN.find(text)?.value?.let { raw -> parseDate(raw)?.let { out += it } }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectDates(child, out, depth + 1)
        }
    }

    /** Une date de l'écran, dans l'un des deux formats mesurés. */
    private fun parseDate(raw: String): LocalDate? {
        for (format in DATE_FORMATS) {
            runCatching { LocalDate.parse(raw, format) }.getOrNull()?.let { return it }
        }
        return null
    }

    private val DATE_PATTERN = Regex("""\d{2,4}/\d{2}/\d{2,4}""")

    /** `aaaa/MM/jj` avant validation de l'intervalle, `jj/MM/aaaa` après. */
    private val DATE_FORMATS = listOf(
        DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ROOT),
        DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT),
    )

    /** Le libellé exact qui a fait correspondre ce nœud. */
    fun matchLabel(node: AccessibilityNodeInfo, labels: List<String>): String? {
        val text = node.text?.toString()
        val description = node.contentDescription?.toString()
        return labels.firstOrNull { label ->
            text?.contains(label, ignoreCase = true) == true ||
                description?.contains(label, ignoreCase = true) == true
        }
    }

    fun find(service: GlookoAccessibilityService, labels: List<String>): AccessibilityNodeInfo? {
        val root = service.root() ?: return null
        val counter = intArrayOf(0)
        return search(root, labels, 0, counter)
    }

    /** Touche le nœud, ou son plus proche ancêtre cliquable. */
    fun click(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
            depth++
        }
        return false
    }

    /** Texte lisible de l'écran, indenté : la matière première de la cartographie. */
    fun dump(service: GlookoAccessibilityService): String {
        val root = service.root() ?: return "(écran illisible)"
        val builder = StringBuilder()
        builder.appendLine("paquet=${root.packageName}")
        dumpNode(root, builder, 0)
        return builder.toString()
    }

    private fun dumpNode(node: AccessibilityNodeInfo, builder: StringBuilder, depth: Int) {
        if (depth > MAX_DEPTH) return
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
        val description = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val id = node.viewIdResourceName
        if (text != null || description != null || id != null) {
            builder.append("  ".repeat(depth))
            if (id != null) builder.append("[").append(id.substringAfterLast('/')).append("] ")
            builder.append(text ?: "")
            if (description != null) builder.append(" {").append(description).append("}")
            if (node.isClickable) builder.append(" (cliquable)")
            builder.appendLine()
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            dumpNode(child, builder, depth + 1)
        }
    }

    private fun search(
        node: AccessibilityNodeInfo,
        labels: List<String>,
        depth: Int,
        counter: IntArray,
    ): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        if (++counter[0] > MAX_NODES) return null
        if (matches(node, labels)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = search(child, labels, depth + 1, counter)
            if (found != null) return found
        }
        return null
    }

    private fun matches(node: AccessibilityNodeInfo, labels: List<String>): Boolean =
        matchLabel(node, labels) != null

    private const val POLL_MS = 200L
}
