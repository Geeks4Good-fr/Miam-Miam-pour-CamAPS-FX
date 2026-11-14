package com.najah.mealannouncer.integration.camaps

/**
 * Type de repas annoncé à CamAPS, tel que l'écran « Ajouter un repas » le propose.
 *
 * Les trois valeurs correspondent, dans l'ordre, aux trois boutons radio relevés sur l'appareil
 * (voir `docs/CAMAPS-REPAS.md` § 4). C'est l'utilisateur qui choisit, vocalement ; l'automate ne devine
 * jamais à sa place.
 */
enum class CamapsMealType {
    /** « Repas ou collation ». */
    MEAL,

    /** « Traitement de l'hypoglycémie ». */
    HYPO,

    /** « Repas à absorption lente ». */
    SLOW,
}

/**
 * Un nœud d'écran réduit à ce dont la décision a besoin.
 *
 * La décision ne dépend pas d'`AccessibilityNodeInfo` (c'est de l'Android, intestable sur la JVM) :
 * la passerelle convertit l'arbre vivant en une liste de ces nœuds, puis applique [CamapsScreen].
 */
data class CamapsNode(
    val text: String? = null,
    val contentDesc: String? = null,
    val className: String? = null,
    val editable: Boolean = false,
    val clickable: Boolean = false,
) {
    /** Les deux libellés possibles d'un nœud, mis bout à bout pour la recherche. */
    internal fun label(): String = listOfNotNull(text, contentDesc).joinToString(" ").trim()
}

/** L'écran CamAPS courant, tel que la passerelle le présente à la décision. */
data class CamapsScreenSnapshot(
    val nodes: List<CamapsNode>,
)

/** Ce que la décision conclut d'un écran. */
sealed interface CamapsDecision {

    /**
     * L'écran est bien « Ajouter un repas » : écrire [carbsText] dans le champ des glucides,
     * cocher le radio portant [mealTypeLabel], et — seulement si [pressContinue] — appuyer sur
     * « Continuer ».
     */
    data class Fill(
        val carbsText: String,
        val mealTypeLabel: String,
        val pressContinue: Boolean,
    ) : CamapsDecision

    /** L'écran n'est pas celui attendu : ne rien toucher, dire [reason]. */
    data class Refuse(val reason: String) : CamapsDecision
}

/**
 * La décision, pure et testable : reconnaître l'écran de repas et refuser tout le reste.
 *
 * La reconnaissance se fait par **libellés français** (stables d'une version à l'autre) et non par
 * les identifiants numériques de vues, qui sont générés et changent à chaque recompilation de
 * CamAPS. En particulier, l'écran du **Calculateur de bolus** (qui dose de l'insuline) est refusé
 * explicitement : il n'est jamais notre cible.
 */
object CamapsScreen {

    /** Titre de l'écran d'ajout de repas. */
    const val TITLE = "Ajouter un repas"

    /** Libellé du radio pour chaque type. */
    val MEAL_TYPE_LABELS: Map<CamapsMealType, String> = mapOf(
        CamapsMealType.MEAL to "Repas ou collation",
        CamapsMealType.HYPO to "Traitement de l'hypoglycémie",
        CamapsMealType.SLOW to "Repas à absorption lente",
    )

    /** Bouton qui valide l'annonce (information à la boucle, pas un bolus). */
    const val CONTINUE_LABEL = "Continuer"

    /** Mots qui trahissent un écran de dosage d'insuline : on s'y refuse toujours. */
    private val FORBIDDEN = listOf("bolus", "calculateur")

    /**
     * Compare deux libellés d'écran sans trébucher sur la casse ni sur l'apostrophe typographique
     * (`’` U+2019 utilisée dans `split_config.fr.apk` de CamAPS FX pour « Traitement de l’hypoglycémie »
     * contre `'` U+0027 dans le code).
     */
    internal fun sameLabel(a: String, b: String): Boolean =
        normalizeLabel(a) == normalizeLabel(b)

    internal fun containsLabel(haystack: String, needle: String): Boolean =
        normalizeLabel(haystack).contains(normalizeLabel(needle))

    internal fun normalizeLabel(s: String): String =
        s.trim()
            .replace('\u2019', '\'')
            .replace('\u2018', '\'')
            .replace('\u02BC', '\'')
            .replace(Regex("\\s+"), " ")
            .lowercase()

    /**
     * Décide quoi faire sur [snapshot] pour annoncer [carbs] g de type [type].
     *
     * [pressContinue] passe la consigne jusqu'à la [CamapsDecision.Fill] : l'automate n'appuie sur
     * « Continuer » que sur confirmation vocale, décidée en amont.
     */
    fun decide(
        snapshot: CamapsScreenSnapshot,
        carbs: Int,
        type: CamapsMealType,
        pressContinue: Boolean,
    ): CamapsDecision {
        if (carbs <= 0) {
            return CamapsDecision.Refuse("Aucun glucides à annoncer.")
        }

        val labels = snapshot.nodes.map { normalizeLabel(it.label()) }

        // Un écran de dosage d'insuline n'est jamais notre cible, même s'il ressemble.
        if (labels.any { label -> FORBIDDEN.any { label.contains(it) } }) {
            return CamapsDecision.Refuse("Écran de dosage d'insuline : l'automate ne s'y risque pas.")
        }

        val isMealScreen = labels.any { containsLabel(it, TITLE) }
        if (!isMealScreen) {
            return CamapsDecision.Refuse("Écran CamAPS inattendu : « $TITLE » introuvable.")
        }

        // Le champ des glucides : le seul champ éditable de l'écran.
        val hasCarbsField = snapshot.nodes.any { it.editable }
        if (!hasCarbsField) {
            return CamapsDecision.Refuse("Champ des glucides introuvable.")
        }

        val mealTypeLabel = MEAL_TYPE_LABELS.getValue(type)
        val hasRadio = snapshot.nodes.any { sameLabel(it.label(), mealTypeLabel) }
        if (!hasRadio) {
            return CamapsDecision.Refuse("Type de repas « $mealTypeLabel » introuvable à l'écran.")
        }

        if (pressContinue && labels.none { containsLabel(it, CONTINUE_LABEL) }) {
            return CamapsDecision.Refuse("Bouton « $CONTINUE_LABEL » introuvable.")
        }

        return CamapsDecision.Fill(
            carbsText = carbs.toString(),
            mealTypeLabel = mealTypeLabel,
            pressContinue = pressContinue,
        )
    }
}
