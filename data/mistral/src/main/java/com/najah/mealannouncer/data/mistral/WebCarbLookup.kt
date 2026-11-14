package com.najah.mealannouncer.data.mistral

import javax.inject.Inject

/**
 * Une valeur trouvée sur Internet : des glucides pour 100 grammes, et d'où ils viennent.
 *
 * [sourceUrl] n'est pas décoratif : quand un chiffre ne vient pas d'Open Food Facts, il
 * faut pouvoir montrer sur quoi il repose avant de le recopier dans CamAPS.
 */
data class WebCarbEstimate(
    val name: String,
    val carbsPer100g: Double,
    val sourceUrl: String?,
    /**
     * Vrai quand la valeur n'est pas une mesure trouvée (base ou web) mais une **estimation** de
     * connaissance commune — le filet final, à présenter comme telle et à confirmer avant d'écrire.
     */
    val general: Boolean = false,
    /** Glucides d'**une** pièce, quand la fiche de l'emballage les donne (« par pain : 22 g »). */
    val carbsPerUnit: Double? = null,
    /** Poids d'**une** pièce, lu sur la fiche ou déduit du paquet (poids du paquet ÷ nombre de pièces). */
    val unitGrams: Double? = null,
    /** D'où vient la valeur par pièce, en clair : « paquet de 8 × 45 g ». Affiché sous la ligne. */
    val unitDetail: String? = null,
    /**
     * Photo du produit **sur la page même** qui a donné la valeur. Retenue seulement si elle est
     * hébergée sur le même site que [sourceUrl] : une image venue d'ailleurs pourrait montrer un
     * autre produit, et il vaut mieux pas de photo qu'une photo trompeuse.
     */
    val imageUrl: String? = null,
    /** Code-barres (EAN) lu sur la fiche, quand elle le donne : il désigne le produit sans ambiguïté. */
    val barcode: String? = null,
) {
    /** Vrai si l'on sait chiffrer une pièce : par ses glucides, ou par son poids. */
    val knowsUnit: Boolean get() = carbsPerUnit != null || unitGrams != null
}

/**
 * Recherche des glucides d'un aliment absent d'Open Food Facts.
 *
 * Le modèle cherche, mais c'est [parse] qui juge : une valeur hors des bornes physiques
 * (0 à 100 g de glucides aux 100 g) est refusée, et une absence de valeur est un échec
 * explicite, pas un zéro.
 */
class WebCarbResearcher @Inject constructor(
    private val webSearch: MistralWebSearch,
) {

    /** Cherche les glucides de [food]. Lève [MistralException] si rien de fiable n'est trouvé. */
    fun lookup(apiKey: String, food: String): WebCarbEstimate {
        val answer = webSearch.ask(apiKey = apiKey, question = question(food), instructions = INSTRUCTIONS)
        return parse(food, answer)
    }

    private fun question(food: String): String =
        "Fiche nutritionnelle de « $food » : glucides pour 100 grammes, et, si c'est un produit " +
            "qui se compte à la pièce, glucides ou poids d'une pièce (nombre de pièces et poids du paquet)."

    companion object {

        val INSTRUCTIONS: String = """
            Tu cherches sur Internet la fiche nutritionnelle d'un aliment : de préférence la fiche
            de l'EMBALLAGE du produit (site de l'enseigne ou de la marque, Open Food Facts, sites de
            fiches nutritionnelles).

            Ce que tu rends :
            - « carbs_per_100g » : les glucides TOTAUX (pas nets), en grammes pour 100 grammes.
            - Si l'aliment se compte à la pièce (pain au chocolat, biscuit, yaourt, barre…) :
              - « carbs_per_unit » : les glucides d'UNE pièce, si la fiche les donne (colonne « par
                pièce » / « par portion d'une pièce ») ;
              - « unit_weight_g » : le poids d'UNE pièce. S'il n'est pas écrit, déduis-le du paquet
                standard : « package_weight_g » ÷ « units_per_package », et rends aussi ces deux
                nombres ;
              - « unit_detail » : en clair, d'où vient la valeur par pièce (« paquet de 8 × 45 g »,
                « fiche : 1 pain = 22 g de glucides »).
              Sinon, ces champs valent null.
            - « name » : le nom simple du produit, marque comprise, SANS le nombre de pièces ni le
              poids du paquet (« pains au chocolat Carrefour », pas « 8 pains au chocolat
              Carrefour (paquet de 360 g) ») : ces nombres vont dans les champs dédiés.
            - « barcode » : le code-barres (EAN, 8 à 14 chiffres) du produit, s'il figure sur la
              fiche ; sinon null.
            - « image_url » : l'adresse de la photo du produit affichée SUR LA PAGE « source »
              elle-même, seulement si elle montre exactement ce produit (même marque, même
              article). Jamais une image d'une autre page ni d'un produit voisin : dans le doute,
              null.

            Règles absolues :
            - Tu ne réponds que pour l'aliment demandé (la marque et l'enseigne comptent). Jamais de
              valeur empruntée à un aliment voisin.
            - Si tu ne trouves pas de valeur fiable, tu ne l'inventes pas : tu réponds par une erreur.
            - Si l'aliment est une boisson, tu cherches les glucides pour 100 millilitres et tu le dis
              dans le champ « name ».

            Tu réponds UNIQUEMENT par un objet JSON, soit :
            {"name":"pains au chocolat Carrefour","carbs_per_100g":45,"carbs_per_unit":null,"unit_weight_g":45,"units_per_package":8,"package_weight_g":360,"unit_detail":"paquet de 8 × 45 g","source":"https://exemple.fr/…"}
            soit, si tu ne trouves rien :
            {"error":"aucune valeur fiable trouvée"}
        """.trimIndent()

        /** Bornes physiques d'un aliment : au-delà, la réponse n'est pas exploitable. */
        private const val MAX_CARBS_PER_100 = 100.0

        /** Une pièce de plus de 300 g de glucides ou de plus de 3 kg n'est plus une pièce. */
        private const val MAX_CARBS_PER_UNIT = 300.0
        private const val MAX_UNIT_GRAMS = 3_000.0

        private fun number(value: Any?): Double? = when (value) {
            is Number -> value.toDouble()
            is String -> value.replace(',', '.').trim().toDoubleOrNull()
            else -> null
        }?.takeIf { it.isFinite() && it > 0.0 }

        /**
         * Le poids d'une pièce : écrit sur la fiche, sinon déduit du paquet. Hors bornes : rien,
         * plutôt qu'un poids faux.
         */
        private fun unitGrams(root: org.json.JSONObject): Double? {
            val written = number(root.opt("unit_weight_g"))
            val count = number(root.opt("units_per_package"))
            val pack = number(root.opt("package_weight_g"))
            val grams = written ?: if (count != null && pack != null) pack / count else null
            return grams?.takeIf { it <= MAX_UNIT_GRAMS }
        }

        /** Un poids ou un volume de paquet (« 360 g », « 1,5 kg », « 33 cl »). */
        private val PACK_AMOUNT = Regex("""\d+(?:[.,]\d+)?\s*(?:g|kg|ml|cl|l)\b""", RegexOption.IGNORE_CASE)

        /**
         * Le nom du produit tel qu'on le mange, pas tel qu'on l'achète : sans le nombre de pièces
         * en tête (« 8 pains au chocolat », « x6 »), ni une parenthèse ou une fin qui donne le
         * paquet (« (paquet de 360 g) », « - 360 g »). Ces nombres vivent dans le détail par pièce,
         * et un titre « 8 pains… » au-dessus d'une quantité « 2 » prête à confusion.
         */
        internal fun simpleName(raw: String): String {
            var name = raw.trim()
            name = name.replace(Regex("""\([^)]*\)""")) { match ->
                val inside = match.value
                if (PACK_AMOUNT.containsMatchIn(inside) || inside.contains("paquet", ignoreCase = true) ||
                    inside.contains("sachet", ignoreCase = true) || inside.contains("lot", ignoreCase = true)
                ) "" else inside
            }
            // Un nombre suivi d'une minuscule est un compte (« 8 pains ») ; suivi d'une majuscule,
            // il fait partie de la marque (« 7 Up ») et reste.
            name = name.replace(Regex("""^\s*(?:[xX]\s*)?\d+\s*(?:[xX]\s+)?(?=\p{Ll})"""), "")
            name = name.replace(Regex("""[\s,–-]+${PACK_AMOUNT.pattern}\s*$""", RegexOption.IGNORE_CASE), "")
            return name.replace(Regex("""\s{2,}"""), " ").trim().trim(',', '-', '–').trim()
        }

        /** Le site d'une adresse HTTPS, sans « www. » ; `null` si l'adresse n'en est pas une. */
        private fun siteOf(url: String?): String? = try {
            java.net.URL(url ?: return null)
                .takeIf { it.protocol == "https" }
                ?.host
                ?.lowercase()
                ?.removePrefix("www.")
        } catch (error: java.net.MalformedURLException) {
            null
        }

        /**
         * L'image n'est gardée que si elle est sur le même site que la fiche (ou l'un de ses
         * sous-domaines d'images, `images.site.fr`, `cdn.site.fr`) : sinon rien, plutôt qu'une
         * photo qui pourrait montrer un autre produit.
         */
        internal fun sameSiteImage(image: String?, source: String?): String? {
            val imageSite = siteOf(image) ?: return null
            val sourceSite = siteOf(source) ?: return null
            val root = sourceSite.split('.').takeLast(2).joinToString(".")
            return image.takeIf { imageSite == sourceSite || imageSite.endsWith(".$root") || imageSite == root }
        }

        /**
         * Lit la réponse du modèle.
         *
         * [food] sert de nom de repli : c'est le nom que l'utilisateur a dit, pas une
         * reformulation du modèle.
         */
        internal fun parse(food: String, answer: String): WebCarbEstimate {
            val root = extractJsonObject(answer)
            val error = root.optString("error").trim()
            if (error.isNotEmpty()) {
                throw MistralException(
                    MistralErrorKind.INVALID_RESPONSE,
                    "Aucune valeur trouvée pour « $food » : $error",
                )
            }
            val carbs = when (val value = root.opt("carbs_per_100g")) {
                is Number -> value.toDouble()
                is String -> value.replace(',', '.').trim().toDoubleOrNull()
                else -> null
            }
            if (carbs == null || !carbs.isFinite() || carbs <= 0.0 || carbs > MAX_CARBS_PER_100) {
                throw MistralException(
                    MistralErrorKind.INVALID_RESPONSE,
                    "Valeur de glucides inexploitable pour « $food »",
                )
            }
            val name = simpleName(root.optString("name")).ifBlank { food }
            val source = root.optString("source").trim().takeIf { it.startsWith("http") }
            return WebCarbEstimate(
                name = name,
                carbsPer100g = carbs,
                sourceUrl = source,
                carbsPerUnit = number(root.opt("carbs_per_unit"))?.takeIf { it <= MAX_CARBS_PER_UNIT },
                unitGrams = unitGrams(root),
                unitDetail = root.optString("unit_detail").trim().takeIf { it.isNotEmpty() && it != "null" },
                imageUrl = sameSiteImage(root.optString("image_url").trim(), source),
                barcode = root.optString("barcode").filter { it.isDigit() }.takeIf { it.length in 8..14 },
            )
        }
    }
}

/**
 * Estimation de repli : les glucides d'un aliment, de **connaissance commune**, quand ni la base
 * ni le web n'ont rien donné.
 *
 * C'est assumé : il s'agit d'**annoncer** un repas (informer l'algorithme), pas de calculer une
 * dose — on n'est pas au gramme près. L'estimation est toujours marquée comme telle (`general`),
 * et jamais écrite sans confirmation. Le modèle ne refuse pas d'estimer un aliment nommé ; il
 * donne la valeur moyenne généralement admise.
 */
object GeneralCarbEstimate {

    val SYSTEM: String = """
        Tu estimes, de mémoire (connaissance commune), la teneur en glucides d'un aliment courant,
        pour 100 grammes. C'est une ESTIMATION, pas une valeur exacte, et c'est volontaire : il
        s'agit d'annoncer un repas, pas de calculer une dose d'insuline.

        Règles :
        - Glucides TOTAUX en grammes pour 100 grammes d'aliment.
        - Donne la valeur moyenne généralement admise pour cet aliment (usage français courant).
        - Tu réponds TOUJOURS par une estimation plausible, même pour un aliment vague
          (« lait chocolaté » → une valeur moyenne). Tu n'inventes pas un aliment absent, mais tu
          ne refuses jamais d'estimer un aliment qui t'est nommé.
        - Si c'est une boisson, estime pour 100 millilitres et dis-le dans « name ».

        Tu réponds UNIQUEMENT par un objet JSON : {"name":"lait chocolaté","carbs_per_100g":10.5}
    """.trimIndent()

    fun userPrompt(food: String): String =
        "Estime les glucides pour 100 grammes de « ${food.trim()} »."

    /** Lit la réponse comme [WebCarbResearcher.parse], puis la marque comme estimation sans source. */
    fun parse(food: String, answer: String): WebCarbEstimate =
        // Une estimation moyenne ne désigne aucun produit précis : ni source, ni photo, ni code-barres.
        WebCarbResearcher.parse(food, answer).copy(
            general = true,
            sourceUrl = null,
            imageUrl = null,
            barcode = null,
        )
}
