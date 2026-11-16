package com.najah.mealannouncer.feature.settings

import com.najah.mealannouncer.core.datastore.AiSecretStore
import com.najah.mealannouncer.core.datastore.DebugConfig
import com.najah.mealannouncer.core.datastore.DebugSettings
import com.najah.mealannouncer.core.datastore.HomeAssistantSecretStore
import com.najah.mealannouncer.core.datastore.HomeVoiceConfigStore
import com.najah.mealannouncer.core.datastore.MealRecordConfigStore
import com.najah.mealannouncer.core.datastore.NightscoutConfigStore
import com.najah.mealannouncer.core.datastore.ShoppingConfigStore
import com.najah.mealannouncer.core.datastore.SupermarketChain
import com.najah.mealannouncer.core.datastore.VoiceConfigStore
import com.najah.mealannouncer.core.datastore.VoiceEngine
import com.najah.mealannouncer.core.datastore.VoxtralSecretStore
import com.najah.mealannouncer.core.datastore.WakeWordConfigStore
import com.najah.mealannouncer.integration.glooko.GlookoPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Ce qu'une restauration a réellement posé : dit à l'écran, jamais supposé. */
data class ImportReport(val applied: Int, val skipped: List<String> = emptyList()) {
    val isEmpty: Boolean get() = applied == 0
}

/**
 * Sauvegarde de la configuration en un fichier, et restauration.
 *
 * **Pourquoi c'est nécessaire** (constat du 15/11/2026) : la version de test et la version
 * publiée portent deux identifiants différents (`…mealannouncer.debug` et `…mealannouncer`).
 * Ce sont donc deux applications aux yeux d'Android, avec chacune ses réglages. Au moment de
 * passer de l'une à l'autre, tout était à refaire — y compris les clés d'API.
 *
 * Le fichier contient les secrets (clé OpenRouter, secret Nightscout, jeton de la maison), parce
 * que ce sont justement eux qu'on ne veut pas ressaisir. Il est écrit là où l'utilisateur le
 * choisit : c'est à lui de le garder.
 */
@Singleton
class ConfigBackup @Inject constructor(
    private val nightscout: NightscoutConfigStore,
    private val mealRecord: MealRecordConfigStore,
    private val voice: VoiceConfigStore,
    private val voxtralSecret: VoxtralSecretStore,
    private val aiKey: AiSecretStore,
    private val shopping: ShoppingConfigStore,
    private val homeVoice: HomeVoiceConfigStore,
    private val homeToken: HomeAssistantSecretStore,
    private val wakeWord: WakeWordConfigStore,
    private val debug: DebugSettings,
    private val glooko: GlookoPreferences,
) {

    /** Le fichier de sauvegarde, en JSON lisible (accents en clair, une ligne par réglage). */
    fun exportJson(): String {
        val meal = mealRecord.config.value
        val voiceConfig = voice.config.value
        val home = homeVoice.config.value
        val nightscoutConfig = nightscout.config.value
        val debugConfig = debug.config.value
        return JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("savedAt", Instant.now().toString())
            put("nightscout", JSONObject().apply {
                put("baseUrl", nightscoutConfig.baseUrl)
                put("credentialType", nightscoutConfig.credentialType.name)
                put("enabled", nightscoutConfig.enabled)
                put("simulation", nightscoutConfig.simulation)
                put("secret", nightscout.readSecret() ?: JSONObject.NULL)
            })
            put("mealRecord", JSONObject().apply {
                put("camapsAutoInsert", meal.camapsAutoInsert)
                put("suiviDiabetologue", meal.suiviDiabetologue)
                put("glookoCarnet", meal.glookoCarnet)
                put("nightscoutNote", meal.nightscoutNote)
                put("attachPhoto", meal.attachPhoto)
            })
            put("voice", JSONObject().apply {
                put("engine", voiceConfig.engine.name)
                put("voiceId", voiceConfig.voiceId)
                put("consentMistral", voiceConfig.consentMistral)
                put("key", voxtralSecret.read() ?: JSONObject.NULL)
            })
            put("aiKey", aiKey.read() ?: JSONObject.NULL)
            put("shopping", JSONObject().apply {
                put("chains", JSONArray().apply {
                    shopping.config.value.chains.forEach { put(it.id) }
                })
            })
            put("homeVoice", JSONObject().apply {
                put("enabled", home.enabled)
                put("baseUrl", home.baseUrl)
                put("token", homeToken.read() ?: JSONObject.NULL)
            })
            put("wakeWord", JSONObject().apply {
                put("enabled", wakeWord.config.value.enabled)
            })
            put("debug", JSONObject().apply {
                put("debugMode", debugConfig.debugMode)
                put("autoUpdate", debugConfig.autoUpdate)
                put("gistToken", debugConfig.gistToken ?: JSONObject.NULL)
            })
            put("glooko", JSONObject().apply {
                put("enabled", glooko.enabled)
                put("includeSgv", glooko.includeSgv)
                put("windowHours", glooko.windowHours)
                put("destination", glooko.destination.name)
                put("dryRun", glooko.dryRun)
                put("enteredBy", glooko.enteredBy)
                put("device", glooko.device)
                put("shareFolderUri", glooko.shareFolderUri ?: JSONObject.NULL)
                put("exportOnMeal", glooko.exportOnMeal)
                put("cadenceMinutes", glooko.cadenceMinutes)
            })
        }.toString(2)
    }

    /**
     * Applique un fichier de sauvegarde.
     *
     * Chaque section est facultative : un fichier partiel ne vide pas ce qu'il ne nomme pas.
     * Un fichier illisible est refusé par exception, pour que l'écran puisse le dire.
     */
    suspend fun importJson(json: String): ImportReport = withContext(Dispatchers.IO) {
        val root = JSONObject(json)
        val format = root.optString("format")
        require(format == FORMAT) { "Ce fichier n'est pas une sauvegarde Miam-Miam." }
        var applied = 0
        val skipped = mutableListOf<String>()

        root.optJSONObject("nightscout")?.let { section ->
            val current = nightscout.config.value
            nightscout.save(
                current.copy(
                    baseUrl = section.optString("baseUrl", current.baseUrl),
                    credentialType = enumOf(section.optString("credentialType"), current.credentialType),
                    enabled = section.optBoolean("enabled", current.enabled),
                    simulation = section.optBoolean("simulation", current.simulation),
                ),
                section.text("secret"),
            )
            applied++
        }

        root.optJSONObject("mealRecord")?.let { section ->
            val current = mealRecord.config.value
            mealRecord.save(
                current.copy(
                    camapsAutoInsert = section.optBoolean("camapsAutoInsert", current.camapsAutoInsert),
                    suiviDiabetologue = section.optBoolean("suiviDiabetologue", current.suiviDiabetologue),
                    glookoCarnet = section.optBoolean("glookoCarnet", current.glookoCarnet),
                    nightscoutNote = section.optBoolean("nightscoutNote", current.nightscoutNote),
                    attachPhoto = section.optBoolean("attachPhoto", current.attachPhoto),
                ),
            )
            applied++
        }

        root.optJSONObject("voice")?.let { section ->
            val current = voice.config.value
            voice.save(
                current.copy(
                    engine = enumOf(section.optString("engine"), current.engine),
                    voiceId = section.optString("voiceId", current.voiceId),
                    consentMistral = section.optBoolean("consentMistral", current.consentMistral),
                ),
                section.text("key"),
            )
            applied++
        }

        root.text("aiKey")?.let { key ->
            aiKey.write(key)
            applied++
        }

        root.optJSONObject("shopping")?.optJSONArray("chains")?.let { array ->
            var count = 0
            for (index in 0 until array.length()) {
                val id = array.optString(index)
                val chain = SupermarketChain.entries.firstOrNull { it.id == id }
                if (chain != null) {
                    shopping.addChain(chain)
                    count++
                } else {
                    skipped.add("enseigne « $id » inconnue")
                }
            }
            if (count > 0) applied++
        }

        root.optJSONObject("homeVoice")?.let { section ->
            val current = homeVoice.config.value
            homeVoice.save(
                current.copy(
                    enabled = section.optBoolean("enabled", current.enabled),
                    baseUrl = section.optString("baseUrl", current.baseUrl),
                ),
            )
            section.text("token")?.let(homeVoice::saveToken)
            applied++
        }

        root.optJSONObject("wakeWord")?.let { section ->
            wakeWord.save(wakeWord.config.value.copy(enabled = section.optBoolean("enabled", false)))
            applied++
        }

        root.optJSONObject("debug")?.let { section ->
            val current = debug.config.value
            debug.save(
                current.copy(
                    debugMode = section.optBoolean("debugMode", current.debugMode),
                    autoUpdate = section.optBoolean("autoUpdate", current.autoUpdate),
                    gistToken = section.text("gistToken") ?: current.gistToken,
                ),
            )
            applied++
        }

        root.optJSONObject("glooko")?.let { section ->
            runCatching {
                glooko.enabled = section.optBoolean("enabled", glooko.enabled)
                glooko.includeSgv = section.optBoolean("includeSgv", glooko.includeSgv)
                glooko.windowHours = section.optLong("windowHours", glooko.windowHours)
                glooko.destination = enumOf(section.optString("destination"), glooko.destination)
                glooko.dryRun = section.optBoolean("dryRun", glooko.dryRun)
                section.text("enteredBy")?.let { glooko.enteredBy = it }
                section.text("device")?.let { glooko.device = it }
                section.text("shareFolderUri")?.let { glooko.shareFolderUri = it }
                glooko.exportOnMeal = section.optBoolean("exportOnMeal", glooko.exportOnMeal)
                glooko.cadenceMinutes = section.optLong("cadenceMinutes", glooko.cadenceMinutes)
            }.onSuccess { applied++ }.onFailure { skipped.add("Glooko : ${it.message}") }
        }

        ImportReport(applied = applied, skipped = skipped)
    }

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private inline fun <reified T : Enum<T>> enumOf(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    private companion object {
        const val FORMAT = "miam-miam-config"
        const val VERSION = 1
    }
}

/** Le vocabulaire du moteur de voix, tel qu'il est écrit dans le fichier de sauvegarde. */
internal fun voiceEngineNamed(name: String): VoiceEngine =
    VoiceEngine.entries.firstOrNull { it.name == name } ?: VoiceEngine.ANDROID

/** Les réglages de debug nommés dans un fichier de sauvegarde. */
internal fun debugConfigNamed(section: JSONObject?, current: DebugConfig): DebugConfig = current
