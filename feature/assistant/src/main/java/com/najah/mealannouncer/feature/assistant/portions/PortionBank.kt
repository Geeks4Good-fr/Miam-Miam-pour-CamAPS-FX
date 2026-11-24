package com.najah.mealannouncer.feature.assistant.portions

import android.content.Context
import android.net.Uri
import android.util.Log
import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.core.model.FoodSource
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Une portion proposée à l'écran : son poids, son libellé et sa photo (fichier local). */
data class PortionOption(val grams: Double, val label: String?, val imagePath: String?)

/** Un aliment de la banque reconnu dans un nom dit, cru (avant cuisson) ou dans l'état des photos. */
data class BankMatch(val food: PortionFood, val raw: Boolean) {

    /** Glucides pour 100 g dans l'état demandé : avant cuisson, on prend les valeurs crues. */
    val per100g: Nutrients
        get() {
            if (!raw) return food.per100g
            food.cooking?.rawPer100g?.let { return it }
            val ratio = food.cooking?.cookedOverRaw ?: return food.per100g
            return food.per100g.copy(carbs = (food.per100g.carbs * ratio).coerceAtMost(100.0))
        }

    fun toProduct(): FoodProduct {
        val middle = food.portions.getOrNull(food.portions.size / 2)
        return FoodProduct(
            id = PortionBank.productId(food.id, raw),
            barcode = null,
            name = if (raw) "${food.name} — cru (avant cuisson)" else food.name,
            brand = null,
            carbsPer100g = per100g.carbs,
            defaultPortionGrams = middle?.grams?.takeUnless { raw },
            servingLabel = middle?.label?.takeUnless { raw },
            source = FoodSource.PORTION_BANK,
        )
    }
}

/** Ce que le reste de l'appli voit de la banque ; [NONE] pour les tests et en cas de panne. */
interface PortionLookup {
    fun match(name: String): BankMatch?
    fun food(productId: String): PortionFood?
    fun options(productId: String): List<PortionOption>

    companion object {
        val NONE = object : PortionLookup {
            override fun match(name: String): BankMatch? = null
            override fun food(productId: String): PortionFood? = null
            override fun options(productId: String): List<PortionOption> = emptyList()
        }
    }
}

/** Compte rendu d'un import, affiché tel quel dans les réglages. */
data class ImportReport(val imported: Int, val rejected: List<String>, val warnings: List<String>) {
    fun summary(): String = buildString {
        append("$imported aliment(s) importé(s)")
        if (rejected.isNotEmpty()) append(", ${rejected.size} refusé(s)")
        if (warnings.isNotEmpty()) append(", ${warnings.size} à vérifier")
        append('.')
        (rejected + warnings).take(6).forEach { append("\n• ").append(it) }
    }
}

/**
 * La banque de portions en images (`docs/PORTIONS.md`).
 *
 * - **Base embarquée** : `assets/portions/base.zip` (Nutrition5k, CC BY 4.0), décompressée au
 *   premier usage dans le dossier privé de l'appli.
 * - **Paquets importés** : chacun dans son dossier ; un aliment importé remplace celui de la base
 *   (ou d'un import plus ancien) qui porte le même identifiant.
 *
 * Tout reste sur le téléphone : ni les photos ni les valeurs ne partent sur un serveur.
 */
@Singleton
class PortionBank @Inject constructor(
    @ApplicationContext private val context: Context,
) : PortionLookup {

    private class Entry(val food: PortionFood, val dir: File, val fromUser: Boolean)

    @Volatile
    private var entries: Map<String, Entry>? = null

    private val root: File get() = File(context.filesDir, "portions")

    /** Nombre d'aliments : (base, importés). */
    fun counts(): Pair<Int, Int> = all().values.partition { !it.fromUser }.let { (b, u) -> b.size to u.size }

    override fun match(name: String): BankMatch? = matchIn(all().values.map { it.food to it.fromUser }, name)

    override fun food(productId: String): PortionFood? = all()[foodId(productId)]?.food

    override fun options(productId: String): List<PortionOption> {
        if (productId.endsWith(RAW_SUFFIX)) return emptyList() // les photos montrent l'aliment cuit
        val entry = all()[foodId(productId)] ?: return emptyList()
        return entry.food.portions.map { p ->
            PortionOption(p.grams, p.label, p.photo?.let { File(entry.dir, it) }?.takeIf { it.isFile }?.absolutePath)
        }
    }

    /** Importe un paquet `.zip` (portions.json + photos) ou un `portions.json` seul. */
    @Synchronized
    fun import(uri: Uri): ImportReport {
        val target = File(root, "user/${System.currentTimeMillis()}")
        target.mkdirs()
        return try {
            // Lecture au fil de l'eau : un paquet de 56 Mo chargé d'un coup en mémoire pourrait pousser
            // Android à tuer d'autres applis — CamAPS compris (incident du 09/11/2026).
            val opened = context.contentResolver.openInputStream(uri)
                ?: return ImportReport(0, listOf("fichier illisible"), emptyList())
            java.io.BufferedInputStream(opened).use { input ->
                input.mark(4)
                val isZip = input.read() == 'P'.code && input.read() == 'K'.code
                input.reset()
                if (isZip) unzip(input, target) else File(target, PACK_JSON).outputStream().use { input.copyTo(it) }
            }
            val json = File(target, PACK_JSON).takeIf { it.isFile }?.readText()
                ?: return ImportReport(0, listOf("portions.json absent du paquet"), emptyList()).also { target.deleteRecursively() }
            val reading = PortionPack.parse(json) { File(target, it).isFile }
            if (reading.foods.isEmpty()) {
                target.deleteRecursively()
            } else {
                // On ne garde que les aliments retenus : un import partiel reste propre.
                val kept = JSONObject().put("format", PortionPack.FORMAT).put("version", PortionPack.VERSION)
                    .put("auteur", reading.author ?: "import")
                    .put("aliments", JSONArray().apply { reading.foods.forEach { put(it.raw) } })
                File(target, PACK_JSON).writeText(kept.toString(2))
                entries = null
            }
            ImportReport(reading.foods.size, reading.rejected, reading.warnings)
        } catch (error: Exception) {
            target.deleteRecursively()
            ImportReport(0, listOf("import impossible : ${error.message ?: error::class.simpleName}"), emptyList())
        }
    }

    /** Exporte les aliments importés (ou toute la banque si [includeBase]) dans un `.zip`. */
    fun export(uri: Uri, includeBase: Boolean = false): Int {
        val chosen = all().values.filter { includeBase || it.fromUser }
        context.contentResolver.openOutputStream(uri)?.use { out ->
            ZipOutputStream(out).use { zip ->
                val foods = JSONArray()
                chosen.forEach { entry ->
                    val raw = JSONObject(entry.food.raw.toString())
                    raw.optJSONArray("portions")?.let { portions ->
                        for (i in 0 until portions.length()) {
                            val p = portions.optJSONObject(i) ?: continue
                            val photo = p.optString("photo").ifBlank { null } ?: continue
                            val file = File(entry.dir, photo)
                            if (!file.isFile) continue
                            val name = "photos/${entry.food.id}/${file.name}"
                            zip.putNextEntry(ZipEntry(name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                            p.put("photo", name)
                        }
                    }
                    foods.put(raw)
                }
                val pack = JSONObject().put("format", PortionPack.FORMAT).put("version", PortionPack.VERSION)
                    .put("auteur", "export Miam-Miam").put("aliments", foods)
                zip.putNextEntry(ZipEntry(PACK_JSON)); zip.write(pack.toString(2).toByteArray()); zip.closeEntry()
            }
        }
        return chosen.size
    }

    /** Relit la banque au prochain usage : après l'installation automatique de la grande banque. */
    @Synchronized
    fun reload() {
        entries = null
    }

    /** Le dossier de la grande banque téléchargée (dossier externe de l'appli, comme les autres paquets). */
    private fun downloadedDir(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, DOWNLOADED_DIR)

    /** Efface tous les aliments importés ; la base embarquée reste. */
    @Synchronized
    fun clearImported() {
        File(root, "user").deleteRecursively()
        entries = null
    }

    @Synchronized
    private fun all(): Map<String, Entry> {
        entries?.let { return it }
        val loaded = LinkedHashMap<String, Entry>()
        baseDir()?.let { dir -> read(dir).forEach { loaded[it.id] = Entry(it, dir, fromUser = false) } }
        // La grande banque téléchargée à l'installation (paquet hors ligne « portions-modele ») :
        // elle complète la base embarquée, et les imports de l'utilisateur passent devant elle.
        downloadedDir().takeIf { File(it, PACK_JSON).isFile }?.let { dir ->
            read(dir).forEach { loaded[it.id] = Entry(it, dir, fromUser = false) }
        }
        File(root, "user").listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            read(dir).forEach { loaded[it.id] = Entry(it, dir, fromUser = true) }
        }
        Log.i(TAG, "Banque de portions : ${loaded.size} aliments")
        return loaded.also { entries = it }
    }

    private fun read(dir: File): List<PortionFood> =
        File(dir, PACK_JSON).takeIf { it.isFile }?.let { PortionPack.parse(it.readText()) { p -> File(dir, p).isFile }.foods }.orEmpty()

    /** La base embarquée, décompressée une fois (et de nouveau si l'appli en apporte une autre). */
    private fun baseDir(): File? = try {
        val dir = File(root, "base")
        val stamp = File(dir, ".version")
        // Une nouvelle installation de l'appli peut apporter une nouvelle base : on la redécompresse.
        val size = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (!stamp.isFile || stamp.readText() != size) {
            dir.deleteRecursively(); dir.mkdirs()
            context.assets.open(BASE_ASSET).use { unzip(it, dir) }
            stamp.writeText(size)
        }
        dir
    } catch (error: Exception) {
        Log.w(TAG, "Base de portions illisible", error)
        null
    }

    private fun unzip(input: InputStream, target: File) {
        val canonical = target.canonicalPath + File.separator
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(target, entry.name)
                // Garde-fou « zip slip » : aucun fichier ne sort du dossier du paquet.
                if (!out.canonicalPath.startsWith(canonical)) continue
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MealAnnouncerPortions"

        /** Sous-dossier de la grande banque téléchargée (paquet hors ligne `modele-portions.zip`). */
        const val DOWNLOADED_DIR = "portions-modele"
        private const val PACK_JSON = "portions.json"
        private const val BASE_ASSET = "portions/base.zip"
        private const val PREFIX = "bank:"
        private const val RAW_SUFFIX = ":cru"

        fun productId(foodId: String, raw: Boolean) = PREFIX + foodId + if (raw) RAW_SUFFIX else ""
        fun isBankProduct(productId: String) = productId.startsWith(PREFIX)
        private fun foodId(productId: String) = productId.removePrefix(PREFIX).removeSuffix(RAW_SUFFIX)

        private val STOP = setOf("de", "du", "des", "la", "le", "les", "au", "aux", "et", "un", "une", "a", "en")
        private val RAW_WORDS = Regex("\\b(cru|crus|crue|crues|a cuire|sec|secs|seche|seches|non cuit|non cuits|avant cuisson)\\b")

        fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
                .lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()

        private fun head(words: String) = words.split(' ').firstOrNull { it.length >= 3 && it !in STOP }

        /**
         * L'aliment de la banque que désigne [name] : un nom ou synonyme contenu dans le nom dit,
         * **et** le même mot principal (« jus de pomme » ne devient pas « pomme », ni « pain au
         * chocolat » « pain de mie »). Le plus long synonyme gagne ; à égalité, un import de
         * l'utilisateur, puis l'aliment le plus courant en France.
         */
        internal fun matchIn(foods: List<Pair<PortionFood, Boolean>>, name: String): BankMatch? {
            val said = normalize(name)
            val saidHead = head(said) ?: return null
            var best: Triple<Int, Int, PortionFood>? = null
            for ((food, fromUser) in foods) {
                for (synonym in listOf(food.name) + food.synonyms) {
                    val s = normalize(synonym)
                    if (s.isEmpty() || head(s) != saidHead) continue
                    if (!Regex("(^| )${Regex.escape(s)}( |$)").containsMatchIn(said)) continue
                    val score = s.length * 10 + (if (fromUser) 5 else 0) + food.frenchFrequency
                    if (best == null || score > best.first) best = Triple(score, 0, food)
                }
            }
            val food = best?.third ?: return null
            val raw = RAW_WORDS.containsMatchIn(said) && food.cooking?.changesWithCooking == true
            return BankMatch(food, raw)
        }
    }
}
