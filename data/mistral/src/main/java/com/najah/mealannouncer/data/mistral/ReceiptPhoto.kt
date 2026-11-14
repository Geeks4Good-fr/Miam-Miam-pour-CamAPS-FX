package com.najah.mealannouncer.data.mistral

import org.json.JSONException
import javax.inject.Inject

/**
 * Un article alimentaire lu sur une facture de courses (Drive, PDF, texte) ou un ticket de
 * caisse photographié.
 */
data class ReceiptItem(
    /** Nom complet et décodé du produit (« 8 pains au chocolat », « Brioche tranchée »). */
    val name: String,
    /** Marque du produit ou de l'enseigne (« Carrefour Classic », « Pasquier », « Marque Repère »). */
    val brand: String? = null,
    /** Code-barres EAN (8 à 14 chiffres) s'il figure sur la facture. */
    val barcode: String? = null,
    /**
     * Produit frais vendu au poids, sans marque (fruits, légumes, herbes, pain de boulangerie) :
     * il ne doit jamais être relié à un produit industriel transformé (essai du 10/11/2026 :
     * « bananes » → « bananes séchées », quatre fois plus de glucides).
     */
    val fresh: Boolean = false,
)

/** Ce que la lecture d'une facture ou d'un ticket de caisse a donné. */
data class ReceiptReading(
    /** Nom de l'enseigne détectée en en-tête (« Carrefour », « E.Leclerc », « Système U »…). */
    val storeChain: String? = null,
    val items: List<ReceiptItem>,
)

object ReceiptExtraction {

    private const val MAX_ITEMS = 80
    private const val MAX_NAME_LENGTH = 90

    val SYSTEM_PROMPT: String = """
        Tu lis une facture de courses (Drive, supermarché) ou un ticket de caisse, et tu en extrais
        la liste des produits ALIMENTAIRES et BOISSONS achetés.

        Règles absolues :
        - « enseigne » : le nom du magasin ou de l'enseigne s'il est visible (« Carrefour »,
          « E.Leclerc », « Système U », « Intermarché », « Auchan », « Lidl », « Aldi »,
          « Monoprix », « Casino », « Picard »…), sinon null.
        - « articles » : uniquement les aliments et boissons. Ignore les articles non alimentaires
          (sacs, lessive, papier toilette, hygiène, remises, totaux, TVA).
        - Sur un ticket de caisse abrégé (« 8 PAINS CHOC CRF 360G », « BRIOCHE TR HARRYS 500G »),
          reconstitue un nom français propre et complet pour la recherche dans Open Food Facts
          (« 8 pains au chocolat », « Brioche tranchée ») et mets la marque dans « marque »
          (« Carrefour », « Harrys »). Si l'article est de la marque de l'enseigne du ticket
          (ex. « CRF », « SIMPL », « ECO+ », « U »), indique le nom de cette marque/enseigne.
        - Si un code-barres (EAN à 8 ou 13 chiffres) est écrit à côté de l'article, mets-le dans
          « code_barres », sinon null. N'invente jamais un code-barres.
        - « frais » : true pour un produit frais vendu au poids ou à l'unité SANS marque
          (fruits, légumes, herbes, pain de boulangerie, fromage ou viande à la coupe), sinon
          false. Garde son nom exact (« Bananes », pas « Bananes séchées »).

        Tu réponds UNIQUEMENT par un objet JSON de cette forme :
        {"enseigne":"Carrefour","articles":[{"nom":"8 pains au chocolat","marque":"Carrefour","code_barres":null,"frais":false},{"nom":"Bananes","marque":null,"code_barres":null,"frais":true}]}
    """.trimIndent()

    fun parse(json: String): ReceiptReading {
        val root = extractJsonObject(json)
        val store = root.optString("enseigne").takeIf { !root.isNull("enseigne") }?.trim()?.takeIf { it.isNotEmpty() }
        val array = try {
            root.optJSONArray("articles")
        } catch (_: JSONException) {
            null
        } ?: throw MistralException(
            MistralErrorKind.INVALID_RESPONSE,
            "La réponse ne contient pas la liste des articles du ticket.",
        )
        val items = buildList {
            for (i in 0 until array.length()) {
                if (size >= MAX_ITEMS) break
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("nom").replace(Regex("\\s+"), " ").trim().take(MAX_NAME_LENGTH)
                if (name.length < 2) continue
                val brand = obj.optString("marque").takeIf { !obj.isNull("marque") }?.trim()?.takeIf { it.isNotEmpty() }
                val barcode = PackagePhotoInterpretation.parseBarcode(obj.opt("code_barres"), null)
                val fresh = obj.optBoolean("frais", false)
                add(ReceiptItem(name = name, brand = brand, barcode = barcode, fresh = fresh))
            }
        }
        return ReceiptReading(storeChain = store, items = items)
    }
}

interface ReceiptReader {
    fun readImage(apiKey: String, jpeg: ByteArray): ReceiptReading
    fun readText(apiKey: String, text: String): ReceiptReading
}

class HttpReceiptReader @Inject constructor(
    private val chat: MistralChat,
) : ReceiptReader {

    override fun readImage(apiKey: String, jpeg: ByteArray): ReceiptReading =
        ReceiptExtraction.parse(
            chat.askJson(
                apiKey = apiKey,
                ask = JsonAsk(
                    system = ReceiptExtraction.SYSTEM_PROMPT,
                    user = "Extrais l'enseigne et les articles alimentaires de cette facture ou de ce ticket de caisse.",
                    imageDataUri = PlateInterpretation.dataUri(jpeg),
                    model = AI_VISION_MODEL,
                ),
            ),
        )

    override fun readText(apiKey: String, text: String): ReceiptReading =
        ReceiptExtraction.parse(
            chat.askJson(
                apiKey = apiKey,
                ask = JsonAsk(
                    system = ReceiptExtraction.SYSTEM_PROMPT,
                    user = "Texte de la facture de courses :\n${text.take(12_000)}\n\nRends le JSON.",
                ),
            ),
        )
}
