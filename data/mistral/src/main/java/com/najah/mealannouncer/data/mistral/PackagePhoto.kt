package com.najah.mealannouncer.data.mistral

import javax.inject.Inject

/**
 * Ce qu'une photo de l'emballage (ou de ce qui a été mangé) apprend sur un aliment : ses
 * glucides pour 100 g et le poids d'**une** pièce.
 *
 * La photo est lue **à l'aveugle** : le modèle ne connaît pas le repas. Essai du 10/11/2026 :
 * à qui l'on donnait la liste des aliments, il a « vu » un paquet de chips Brets (nom recopié
 * de la liste, 52 g pour 100 g, sachet de 30 g) sur la photo d'une barre de pâté en croûte à
 * moitié mangée. C'est l'application qui rattache ensuite la photo à une ligne du repas.
 *
 * Chaque nombre est `null` quand il n'est pas lisible **dans [printedText]** (le texte recopié
 * de l'emballage) : un chiffre absent de ce texte est une invention, il est jeté.
 */
data class PackageReading(
    /** Ce que le modèle a vu, en une ou deux phrases à dire à voix haute. */
    val description: String?,
    /** L'aliment visible, en quelques mots (« pâté en croûte », « chips nature »). */
    val visibleFood: String?,
    /** Le nom du produit lu sur l'emballage, ou `null`. */
    val product: String?,
    /** Le code-barres (EAN-8, UPC, EAN-13) lu sous les barres, ou `null`. */
    val barcode: String? = null,
    /** Le texte imprimé recopié mot pour mot (tableau, poids) : la preuve de chaque nombre. */
    val printedText: String?,
    val unitGrams: Double?,
    val carbsPerUnit: Double?,
    val piecesPerPack: Double?,
    val netGrams: Double?,
    val carbsPer100g: Double?,
    /** Ce que la photo montre de la quantité mangée (« la moitié »), ou `null`. */
    val eaten: String?,
    val readOnPackage: Boolean,
) {
    /**
     * Le poids d'une pièce : lu tel quel, sinon déduit du paquet (poids net ÷ nombre de
     * pièces), comme le fait la recherche web.
     */
    val pieceGrams: Double?
        get() = unitGrams ?: run {
            val net = netGrams ?: return@run null
            val count = piecesPerPack?.takeIf { it >= 1.0 } ?: return@run null
            net / count
        }

    /** Vrai si la photo permet de chiffrer une pièce. */
    val knowsUnit: Boolean get() = carbsPerUnit != null || pieceGrams != null
}

/**
 * Lecture d'une photo d'emballage pendant un repas.
 *
 * Le modèle décrit et recopie ; il ne choisit rien et n'invente aucun glucide. Les nombres
 * qu'il rend ne sont gardés que s'ils figurent dans le texte qu'il dit avoir lu.
 */
object PackagePhotoInterpretation {

    private const val MAX_PIECE_GRAMS = 2_000.0
    private const val MAX_NET_GRAMS = 10_000.0
    private const val MAX_PIECES = 200.0
    private const val MAX_DESCRIPTION = 400
    private const val MAX_PRINTED = 1_200

    val SYSTEM_PROMPT: String = """
        Tu regardes une photo prise pendant un repas. Tu dis ce que tu vois, rien de plus.

        Règles absolues :
        - Décris UNIQUEMENT ce qui est sur la photo. N'imagine aucun autre objet, aucun autre
          paquet, aucune autre marque. Si tu hésites, dis que tu n'es pas sûr.
        - « description » : une ou deux phrases courtes, en français parlé (« Je vois une barre
          de pâté en croûte à moitié mangée, dans son emballage ; le tableau nutritionnel n'est
          pas visible. »). Dis toujours si un tableau nutritionnel est lisible ou non. Écris les
          nombres en chiffres et les unités en toutes lettres.
        - « aliment_visible » : l'aliment que tu vois, en quelques mots (« pâté en croûte »).
        - « produit » : le nom du produit et la marque SEULEMENT s'ils sont imprimés et lisibles,
          sinon null.
        - « code_barres » : les chiffres du code-barres (EAN-8 ou EAN-13, 8 à 14 chiffres)
          SEULEMENT s'ils sont imprimés et lisibles sous le code-barres, sinon null. N'invente
          JAMAIS un code-barres de mémoire.
        - « texte_lu » : recopie MOT POUR MOT le texte imprimé lisible qui sert au calcul : la
          ligne « Glucides » du tableau, le poids net, le nombre de pièces, la portion, le
          code-barres. Rien d'autre, et null si tu ne lis aucun texte.
        - Chaque nombre ci-dessous doit figurer dans « texte_lu ». Sinon : null. Ne complète
          jamais avec ta mémoire ou une valeur habituelle.
        - « glucides_100g » : la ligne « Glucides » pour 100 g (ou 100 ml) — jamais la ligne
          « dont sucres ».
        - « poids_piece_g » : le poids d'une pièce imprimé (« 1 pâté = 45 g », « portion :
          1 barre de 21,5 g »).
        - « pieces_par_paquet » et « poids_net_g » : le nombre de pièces et le poids net du
          paquet, s'ils sont imprimés. Pour un produit vendu à l'unité, pieces_par_paquet = 1.
        - « glucides_piece_g » : seulement si le tableau donne les glucides PAR pièce.
        - « lu_sur_emballage » : true si un poids est lu en toutes lettres ; false sinon.
        - « mange » : ce que la photo montre de la quantité mangée (« la moitié »), sinon null.
        - Aucun conseil, aucune calorie, aucune insuline.

        Tu réponds UNIQUEMENT par un objet JSON de cette forme :
        (exemple d'une AUTRE photo, ne recopie pas ses valeurs)
        {"description":"Je vois le dos d'un sachet de madeleines ; le tableau est lisible : 55 grammes de glucides pour 100 grammes, 12 madeleines de 25 grammes.","aliment_visible":"madeleines","produit":"Madeleines Exemple","code_barres":"3560070711994","texte_lu":"Glucides 55 g ... 12 madeleines ... 300 g ... 1 madeleine (25 g) ... 3560070711994","glucides_100g":55,"poids_piece_g":25,"pieces_par_paquet":12,"poids_net_g":300,"glucides_piece_g":null,"lu_sur_emballage":true,"mange":null}
    """.trimIndent()

    /** La question posée avec la photo : rien sur le repas, pour ne pas souffler de réponse. */
    const val USER_PROMPT: String = "Décris cette photo et rends le JSON."

    fun parse(json: String): PackageReading {
        val root = extractJsonObject(json)
        fun text(key: String, max: Int = 80): String? =
            root.optString(key).takeIf { !root.isNull(key) }?.trim()?.takeIf { it.isNotEmpty() }?.take(max)
        val printed = text("texte_lu", MAX_PRINTED)
        fun number(key: String, max: Double, mustBePrinted: Boolean = true): Double? {
            val value = when (val raw = root.opt(key)) {
                is Number -> raw.toDouble()
                is String -> raw.replace(',', '.').trim().toDoubleOrNull()
                else -> null
            } ?: return null
            if (!value.isFinite() || value <= 0.0 || value > max) return null
            // Un nombre que le modèle n'a pas recopié de l'emballage est une invention.
            if (mustBePrinted && !isPrinted(value, printed)) return null
            return value
        }
        val readOnPackage = root.optBoolean("lu_sur_emballage", false)
        val carbsPerUnit = number("glucides_piece_g", MAX_PIECE_GRAMS)
        // Un poids de pièce estimé à l'œil (lu_sur_emballage = false) n'a pas à être imprimé.
        val unitGrams = number("poids_piece_g", MAX_PIECE_GRAMS, mustBePrinted = readOnPackage)
        val pieces = when {
            // « 1 » pour un produit vendu à l'unité : rarement écrit tel quel sur l'emballage.
            number("pieces_par_paquet", MAX_PIECES, mustBePrinted = false) == 1.0 -> 1.0
            else -> number("pieces_par_paquet", MAX_PIECES)
        }
        val barcode = parseBarcode(root.opt("code_barres"), printed)
        return PackageReading(
            description = text("description", MAX_DESCRIPTION),
            visibleFood = text("aliment_visible"),
            product = text("produit"),
            barcode = barcode,
            printedText = printed,
            unitGrams = unitGrams,
            // Des glucides d'une pièce plus lourds que la pièce : lecture fausse, on les jette.
            carbsPerUnit = carbsPerUnit?.takeIf { unitGrams == null || it <= unitGrams },
            piecesPerPack = pieces,
            netGrams = number("poids_net_g", MAX_NET_GRAMS),
            carbsPer100g = number("glucides_100g", 100.0),
            eaten = text("mange"),
            readOnPackage = readOnPackage && printed != null,
        )
    }

    /**
     * Vrai si [value] apparaît dans [printed], écrit à la française ou à l'anglaise
     * (« 6,5 », « 6.5 ») ; un entier peut aussi s'écrire « 18,0 ».
     */
    internal fun isPrinted(value: Double, printed: String?): Boolean {
        if (printed.isNullOrBlank()) return false
        val whole = value.toLong()
        val pattern = if (value == whole.toDouble()) {
            "(?<![0-9.,])$whole(?:[.,]0+)?(?![0-9]|[.,][0-9])"
        } else {
            val text = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
            val (intPart, decimals) = text.split('.').let { it[0] to it.getOrElse(1) { "" } }
            "(?<![0-9.,])$intPart[.,]${decimals}0*(?![0-9])"
        }
        return Regex(pattern).containsMatchIn(printed)
    }

    /**
     * Extrait un code-barres (EAN-8 à GTIN-14, 8 à 14 chiffres) s'il est valide et, lorsque
     * [printed] est présent, vérifie ou retrouve les chiffres recopiés de l'emballage.
     */
    internal fun parseBarcode(raw: Any?, printed: String?): String? {
        val candidate = when (raw) {
            is Number -> raw.toLong().toString()
            is String -> raw.filter { it.isDigit() }
            else -> ""
        }
        if (candidate.length in 8..14) {
            val printedDigits = printed?.filter { it.isDigit() }.orEmpty()
            if (printedDigits.isEmpty() || printedDigits.contains(candidate)) {
                return candidate
            }
        }
        // Si le modèle a recopié l'EAN dans `texte_lu` sans remplir `code_barres`.
        if (!printed.isNullOrBlank()) {
            Regex("\\b(\\d{8}|\\d{12,14})\\b").find(printed.replace(Regex("(?<=\\d)\\s+(?=\\d)"), ""))
                ?.groupValues?.get(1)
                ?.let { return it }
        }
        return null
    }
}

/** Lit une photo d'emballage. Lève [MistralException] quand l'appel échoue. */
interface PackagePhotoReader {
    fun read(apiKey: String, jpeg: ByteArray): PackageReading
}

class HttpPackagePhotoReader @Inject constructor(
    private val chat: MistralChat,
) : PackagePhotoReader {

    override fun read(apiKey: String, jpeg: ByteArray): PackageReading =
        PackagePhotoInterpretation.parse(
            chat.askJson(
                apiKey = apiKey,
                ask = JsonAsk(
                    system = PackagePhotoInterpretation.SYSTEM_PROMPT,
                    user = PackagePhotoInterpretation.USER_PROMPT,
                    imageDataUri = PlateInterpretation.dataUri(jpeg),
                    model = AI_VISION_MODEL,
                ),
            ),
        )
}
