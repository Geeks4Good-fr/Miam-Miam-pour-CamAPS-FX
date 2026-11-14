package com.najah.mealannouncer.data.mistral

import org.json.JSONException
import java.util.Base64
import javax.inject.Inject

/**
 * Un aliment vu sur une photo, avec le poids que la photo permet d'estimer.
 *
 * [grams] est **nullable** : une assiette dit rarement son poids, et un modèle qui n'ose pas
 * l'estimer doit pouvoir le dire. Une proposition sans poids suivra alors le chemin ordinaire
 * de la portion — c'est l'utilisateur qui la donnera.
 */
data class PlateFood(
    val name: String,
    val grams: Double?,
    val barcode: String? = null,
)

/** Ce qu'une photo a donné : les aliments vus, et ceux qu'on a dû écarter. */
data class PlateReading(
    val foods: List<PlateFood>,
    val rejected: List<String> = emptyList(),
    /** Ce que le modèle a vu, en une ou deux phrases à dire à voix haute ; `null` s'il n'a rien dit. */
    val description: String? = null,
)

/**
 * Lecture d'une photo d'assiette.
 *
 * Le modèle regarde et **nomme** : il ne donne aucun glucides, comme partout ailleurs. Le
 * poids estimé n'est qu'une proposition de départ, affichée avec la mention « d'après la
 * photo » et soumise à la confirmation de l'utilisateur : une assiette photographiée de biais,
 * à moitié mangée ou dans une lumière étrange ne vaut pas une pesée.
 */
object PlateInterpretation {

    /** En deçà et au-delà, ce n'est plus une portion mais une erreur de lecture. */
    private const val MIN_GRAMS = 1.0
    private const val MAX_GRAMS = 3_000.0

    /** Au-delà, la photo n'est plus une assiette mais un buffet : on ne liste pas tout. */
    private const val MAX_FOODS = 8

    private const val MAX_NAME_LENGTH = 80

    val SYSTEM_PROMPT: String = """
        Tu regardes la photo d'une assiette, d'un emballage alimentaire ou d'un code-barres, et tu dis ce qu'elle montre.

        Règles absolues :
        - Tu ne listes que ce que tu vois réellement. Rien n'est ajouté par habitude, ni pour
          « compléter » un repas.
        - Si la photo montre un EMBALLAGE de produit alimentaire (sachet, boîte, bouteille, pot,
          paquet) ou un CODE-BARRES, c'est bien un aliment : tu mets le produit dans `foods`
          avec son nom et sa marque tels qu'ils sont imprimés sur l'emballage (par exemple
          « 8 pains au chocolat Carrefour Classic »). Si seul le code-barres est visible sans
          nom lisible, mets `"name": "produit code-barres"` et renseigne `"code_barres"`.
        - « code_barres » : les chiffres du code-barres (EAN-8 ou EAN-13, 8 à 14 chiffres)
          SEULEMENT s'ils sont imprimés et lisibles sur la photo, sinon null. N'invente jamais
          un code-barres.
        - Tu ne donnes AUCUNE valeur de glucides, aucune calorie, aucun conseil d'insuline.
          Tu nommes des aliments et tu proposes un poids.
        - « grammes » est ton estimation du poids visible de cet aliment dans une assiette, ou
          le poids d'une portion / d'une pièce s'il est lisible sur l'emballage. Si la photo
          montre un paquet entier de plusieurs pièces ou seulement un code-barres sans portion
          individuelle, mets `grammes` à null : l'application demandera combien la personne en
          a pris.
        - Un poids hors de 1 à 3000 grammes est une erreur : ne le rends pas.
        - Les noms restent en français et servent à une recherche : « riz basmati », pas « riz »,
          « poulet rôti », pas « viande », et pour un emballage garde la marque et l'intitulé
          imprimés.
        - Un plat composé reste UN seul aliment (« gratin de courgettes », « tajine de poulet ») :
          tu ne le découpes pas en ingrédients, sinon les mêmes glucides seraient comptés deux
          fois.
        - Au plus huit aliments, du plus gros au plus petit.
        - Si la photo ne montre ni nourriture, ni boisson, ni emballage alimentaire, ni
          code-barres, tu rends une liste vide.
        - « description » : une ou deux phrases courtes, en français parlé, qui disent ce que tu
          vois (« Je vois une assiette de riz avec du poulet rôti, environ 150 grammes de riz. »
          ou « Je vois un sachet de 8 pains au chocolat Carrefour Classic. »). Écris les nombres
          en chiffres et les unités en toutes lettres.

        Tu réponds UNIQUEMENT par un objet JSON de cette forme :
        {"description":"Je vois une assiette de riz basmati avec du poulet rôti.","foods":[{"name":"poulet rôti","grammes":120,"code_barres":null},{"name":"riz basmati","grammes":150,"code_barres":null}]}
    """.trimIndent()

    /**
     * La question posée avec la photo.
     *
     * [hint] reprend ce que l'utilisateur a déjà écrit ou dicté, quand il y a quelque chose :
     * cela aide le modèle à nommer juste, sans l'autoriser à inventer.
     */
    fun userPrompt(hint: String? = null): String = buildString {
        appendLine("Photo : une assiette, un emballage alimentaire ou un code-barres.")
        val precision = hint?.trim().orEmpty()
        if (precision.isNotEmpty()) {
            appendLine("L'utilisateur précise : « $precision »")
        }
        appendLine()
        append("Rends le JSON des aliments ou produits visibles.")
    }

    /** La photo en data URI, telle que l'API l'attend. */
    fun dataUri(jpeg: ByteArray): String =
        "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)

    /**
     * Lit la réponse du modèle.
     *
     * Une proposition sans nom exploitable est écartée **et signalée** : la faire disparaître
     * en silence ferait croire que la photo n'a rien montré. Les poids hors bornes, eux,
     * deviennent `null` plutôt que d'être rognés : on garde l'aliment, on jette le chiffre.
     */
    fun parse(json: String): PlateReading {
        val racine = extractJsonObject(json)
        val tableau = try {
            racine.optJSONArray("foods")
        } catch (error: JSONException) {
            null
        } ?: throw MistralException(
            MistralErrorKind.INVALID_RESPONSE,
            "La réponse ne contient pas de liste d'aliments",
        )

        val foods = mutableListOf<PlateFood>()
        val rejected = mutableListOf<String>()
        for (index in 0 until tableau.length()) {
            val item = tableau.optJSONObject(index) ?: continue
            val barcode = PackagePhotoInterpretation.parseBarcode(item.opt("code_barres"), null)
            val rawName = item.optString("name").replace(Regex("\\s+"), " ").trim()
            val name = if (rawName.isEmpty() && barcode != null) "produit code-barres $barcode" else rawName
            if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
                rejected += name.ifBlank { "aliment ${index + 1}" }
                continue
            }
            if (foods.size >= MAX_FOODS) {
                // Au-delà de huit, ce n'est plus une assiette : on le dit au lieu de couper net.
                rejected += name
                continue
            }
            foods += PlateFood(
                name = name,
                grams = lireGrammes(item.opt("grammes")),
                barcode = barcode,
            )
        }
        val description = racine.optString("description").takeIf { !racine.isNull("description") }
            ?.trim()?.takeIf { it.isNotEmpty() }?.take(400)
        return PlateReading(foods = foods, rejected = rejected, description = description)
    }

    private fun lireGrammes(valeur: Any?): Double? {
        val nombre = when (valeur) {
            is Number -> valeur.toDouble()
            is String -> valeur.replace(',', '.').trim().toDoubleOrNull()
            else -> null
        } ?: return null
        if (!nombre.isFinite()) return null
        if (nombre < MIN_GRAMS || nombre > MAX_GRAMS) return null
        return nombre
    }
}

/**
 * Lit une photo d'assiette.
 *
 * L'implémentation ne décide de rien : elle envoie l'image et rend ce que
 * [PlateInterpretation.parse] a bien voulu lire.
 */
interface PlateReader {
    /**
     * Lève [MistralException] quand l'appel échoue ; l'appelant le dit alors à l'écran plutôt
     * que d'afficher des lignes qu'il n'a pas pu vérifier.
     */
    fun read(apiKey: String, jpeg: ByteArray, hint: String? = null): PlateReading
}

/** Envoie la photo au modèle de vision, en mode JSON. */
class HttpPlateReader @Inject constructor(
    private val chat: MistralChat,
) : PlateReader {

    override fun read(apiKey: String, jpeg: ByteArray, hint: String?): PlateReading {
        return PlateInterpretation.parse(
            chat.askJson(
                apiKey = apiKey,
                ask = JsonAsk(
                    system = PlateInterpretation.SYSTEM_PROMPT,
                    user = PlateInterpretation.userPrompt(hint),
                    imageDataUri = PlateInterpretation.dataUri(jpeg),
                    model = AI_VISION_MODEL,
                ),
            ),
        )
    }
}
