package com.najah.mealannouncer.feature.assistant.portions

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.abs

/**
 * Format d'échange des portions en images : « miam-miam-portions », version 1 (`docs/PORTIONS.md`).
 *
 * Un paquet = `portions.json` + des photos. Chaque aliment donne ses valeurs pour 100 g dans l'état
 * de ses portions (cuit si les photos montrent du cuit), et peut donner les valeurs avant cuisson
 * avec le rapport de poids cuit/cru : l'appli peut alors demander « cuit ou à cuire ? ».
 *
 * Tout champ inconnu est gardé dans [PortionFood.raw] : l'utilisateur peut enrichir ses paquets
 * (charge glycémique, saison…) sans attendre une nouvelle version de l'appli.
 */
data class Nutrients(
    val carbs: Double,
    val sugars: Double? = null,
    val fibres: Double? = null,
    val starch: Double? = null,
    val fat: Double? = null,
    val protein: Double? = null,
    val kcal: Double? = null,
    val salt: Double? = null,
)

data class Cooking(
    /** `cuit`, `cru` ou `sans cuisson`. */
    val state: String? = null,
    val mode: String? = null,
    /** Poids cuit ÷ poids cru (riz ≈ 2,5). */
    val cookedOverRaw: Double? = null,
    val rawPer100g: Nutrients? = null,
) {
    /** Vrai si l'aliment change de poids (et donc de glucides pour 100 g) à la cuisson. */
    val changesWithCooking: Boolean get() = state == "cuit" && (cookedOverRaw != null || rawPer100g != null)
}

data class PortionLevel(
    val grams: Double,
    val label: String?,
    /** Chemin de la photo dans le paquet (« photos/riz_75g.jpg »). */
    val photo: String?,
)

data class PortionFood(
    val id: String,
    val name: String,
    val synonyms: List<String>,
    val category: String?,
    val per100g: Nutrients,
    val glycemicIndex: Double?,
    val digestion: String?,
    val cooking: Cooking?,
    val portions: List<PortionLevel>,
    val frenchFrequency: Int,
    val source: String?,
    val photoLicence: String?,
    /** L'objet JSON d'origine, avec ses champs inconnus : rien n'est perdu à l'export. */
    val raw: JSONObject,
) {
    /** Charge glycémique d'une portion de [grams] (IG × glucides ÷ 100), si l'IG est connu. */
    fun glycemicLoad(grams: Double): Double? = glycemicIndex?.let { it * per100g.carbs * grams / 100.0 / 100.0 }
}

/** Ce qu'un import a donné : les aliments retenus, et pourquoi d'autres ont été refusés. */
data class PackReading(
    val foods: List<PortionFood>,
    val rejected: List<String>,
    val warnings: List<String>,
    val author: String?,
)

object PortionPack {
    const val FORMAT = "miam-miam-portions"
    const val VERSION = 1

    private const val MAX_GRAMS = 3_000.0
    private const val MAX_PORTIONS = 8
    private val ID = Regex("^[a-z0-9_\\-]{1,60}$")

    /**
     * Lit `portions.json`. [photoExists] dit si une photo citée est bien dans le paquet. Un aliment
     * incohérent est refusé **avec sa raison** ; un doute (calories qui ne collent pas avec les
     * macronutriments) est seulement signalé.
     */
    fun parse(json: String, photoExists: (String) -> Boolean = { true }): PackReading {
        val root = try {
            JSONObject(json)
        } catch (error: JSONException) {
            return PackReading(emptyList(), listOf("portions.json illisible : ${error.message}"), emptyList(), null)
        }
        if (root.optString("format") != FORMAT) {
            return PackReading(emptyList(), listOf("ce n'est pas un paquet « $FORMAT »"), emptyList(), null)
        }
        if (root.optInt("version", 0) > VERSION) {
            return PackReading(emptyList(), listOf("version ${root.optInt("version")} trop récente : mettez l'appli à jour"), emptyList(), null)
        }
        val foods = mutableListOf<PortionFood>()
        val rejected = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val array = root.optJSONArray("aliments") ?: JSONArray()
        val seen = mutableSetOf<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val label = item.optString("nom").ifBlank { item.optString("id").ifBlank { "aliment ${index + 1}" } }
            val problem = check(item, photoExists)
            if (problem != null) {
                rejected += "$label : $problem"
                continue
            }
            val food = read(item)
            if (!seen.add(food.id)) {
                rejected += "$label : identifiant « ${food.id} » en double"
                continue
            }
            atwaterDoubt(food.per100g)?.let { warnings += "$label : $it" }
            foods += food
        }
        return PackReading(foods, rejected, warnings, root.optString("auteur").ifBlank { null })
    }

    private fun check(item: JSONObject, photoExists: (String) -> Boolean): String? {
        val id = item.optString("id")
        if (!ID.matches(id)) return "identifiant manquant ou invalide (minuscules, chiffres, _ et -)"
        if (item.optString("nom").isBlank()) return "nom manquant"
        val per100 = item.optJSONObject("pour_100g") ?: return "« pour_100g » manquant"
        val carbs = number(per100, "glucides") ?: return "glucides pour 100 g manquants"
        if (carbs < 0 || carbs > 100) return "glucides pour 100 g hors de 0 à 100 ($carbs)"
        listOf("sucres", "fibres", "amidon", "lipides", "proteines").forEach { key ->
            number(per100, key)?.let { if (it < 0 || it > 100) return "« $key » hors de 0 à 100 ($it)" }
        }
        number(per100, "sucres")?.let { if (it > carbs + 0.5) return "plus de sucres ($it) que de glucides ($carbs)" }
        number(item, "index_glycemique")?.let { if (it < 0 || it > 110) return "index glycémique hors de 0 à 110 ($it)" }
        item.optJSONObject("cuisson")?.let { cooking ->
            number(cooking, "rapport_cuit_sur_cru")?.let { if (it < 0.2 || it > 10) return "rapport cuit/cru invraisemblable ($it)" }
        }
        val portions = item.optJSONArray("portions") ?: return "aucune portion"
        if (portions.length() == 0) return "aucune portion"
        if (portions.length() > MAX_PORTIONS) return "plus de $MAX_PORTIONS portions"
        for (i in 0 until portions.length()) {
            val p = portions.optJSONObject(i) ?: return "portion ${i + 1} illisible"
            val grams = number(p, "grammes") ?: return "portion ${i + 1} sans « grammes »"
            if (grams < 1 || grams > MAX_GRAMS) return "portion de $grams g hors de 1 à 3 000 g"
            val photo = p.optString("photo").takeIf { it.isNotBlank() && !p.isNull("photo") }
            if (photo != null && !photoExists(photo)) return "photo manquante : $photo"
        }
        return null
    }

    private fun read(item: JSONObject): PortionFood {
        val per100 = item.getJSONObject("pour_100g")
        val portions = item.getJSONArray("portions").let { array ->
            (0 until array.length()).map { i ->
                val p = array.getJSONObject(i)
                PortionLevel(
                    grams = number(p, "grammes")!!,
                    label = p.optString("libelle").ifBlank { null },
                    photo = p.optString("photo").takeIf { it.isNotBlank() && !p.isNull("photo") },
                )
            }.sortedBy { it.grams }
        }
        val cooking = item.optJSONObject("cuisson")?.let { c ->
            Cooking(
                state = c.optString("etat").ifBlank { null },
                mode = c.optString("mode").ifBlank { null },
                cookedOverRaw = number(c, "rapport_cuit_sur_cru"),
                rawPer100g = c.optJSONObject("pour_100g_cru")?.let { raw -> number(raw, "glucides")?.let { nutrients(raw) } },
            )
        }
        val synonyms = item.optJSONArray("synonymes")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } } }.orEmpty()
        return PortionFood(
            id = item.getString("id"),
            name = item.getString("nom").trim(),
            synonyms = synonyms,
            category = item.optString("categorie").ifBlank { null },
            per100g = nutrients(per100),
            glycemicIndex = number(item, "index_glycemique"),
            digestion = item.optString("digestion").ifBlank { null },
            cooking = cooking,
            portions = portions,
            frenchFrequency = item.optInt("frequence_fr", 1).coerceIn(1, 3),
            source = item.optString("source").ifBlank { null },
            photoLicence = item.optString("photos_licence").ifBlank { null },
            raw = item,
        )
    }

    private fun nutrients(o: JSONObject) = Nutrients(
        carbs = number(o, "glucides") ?: 0.0,
        sugars = number(o, "sucres"),
        fibres = number(o, "fibres"),
        starch = number(o, "amidon"),
        fat = number(o, "lipides"),
        protein = number(o, "proteines"),
        kcal = number(o, "energie_kcal"),
        salt = number(o, "sel"),
    )

    /**
     * Cohérence d'Atwater : 4 kcal par gramme de glucides et de protéines, 9 par gramme de lipides
     * (2 pour les fibres). Un écart de plus de 25 % et 40 kcal trahit souvent une faute de frappe.
     */
    internal fun atwaterDoubt(n: Nutrients): String? {
        val kcal = n.kcal ?: return null
        val fat = n.fat ?: return null
        val protein = n.protein ?: return null
        val expected = 4 * n.carbs + 4 * protein + 9 * fat + 2 * (n.fibres ?: 0.0)
        val gap = abs(expected - kcal)
        return if (gap > 40 && gap > 0.25 * max(expected, kcal)) {
            "énergie de ${kcal.toInt()} kcal pour 100 g, alors que les macronutriments en donnent ≈ ${expected.toInt()} : à vérifier"
        } else {
            null
        }
    }

    private fun max(a: Double, b: Double) = if (a > b) a else b

    private fun number(o: JSONObject, key: String): Double? {
        if (!o.has(key) || o.isNull(key)) return null
        val value = when (val v = o.opt(key)) {
            is Number -> v.toDouble()
            is String -> v.replace(',', '.').trim().toDoubleOrNull()
            else -> null
        } ?: return null
        return value.takeIf { it.isFinite() }
    }
}
