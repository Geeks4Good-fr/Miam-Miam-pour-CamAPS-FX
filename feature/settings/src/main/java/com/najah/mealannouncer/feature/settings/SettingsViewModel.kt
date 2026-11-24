package com.najah.mealannouncer.feature.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.najah.mealannouncer.core.datastore.CredentialType
import com.najah.mealannouncer.core.datastore.MealRecordConfigStore
import com.najah.mealannouncer.core.datastore.NightscoutConfig
import com.najah.mealannouncer.core.datastore.NightscoutConfigStore
import com.najah.mealannouncer.core.datastore.VoiceConfigStore
import com.najah.mealannouncer.core.datastore.VoiceEngine
import com.najah.mealannouncer.data.nightscout.NightscoutErrorKind
import com.najah.mealannouncer.data.nightscout.NightscoutRepository
import com.najah.mealannouncer.data.nightscout.NightscoutServerInfo
import com.najah.mealannouncer.data.nightscout.NightscoutTestResult
import com.najah.mealannouncer.data.nightscout.NightscoutUrl
import com.najah.mealannouncer.feature.voice.MealAnnouncementText
import com.najah.mealannouncer.feature.voice.SpokenBy
import com.najah.mealannouncer.feature.voice.VoiceAnnouncer
import com.najah.mealannouncer.feature.voice.VoiceEngineTts
import com.najah.mealannouncer.feature.voice.VoxtralEngine
import com.najah.mealannouncer.integration.camaps.CamapsAccessibilityService
import com.najah.mealannouncer.integration.glooko.GlookoPreferences
import com.najah.mealannouncer.integration.glookoexport.GlookoAccessibilityService
import com.najah.mealannouncer.integration.glookoexport.GlookoAutomation
import com.najah.mealannouncer.integration.glookoexport.GlookoExportService
import com.najah.mealannouncer.integration.glookoexport.GlookoScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** Ce que dit le bouton « Tester la connexion ». */
sealed interface ConnectionTestState {

    data object Idle : ConnectionTestState

    data object Testing : ConnectionTestState

    data class Success(val server: NightscoutServerInfo) : ConnectionTestState

    data class Failure(val kind: NightscoutErrorKind) : ConnectionTestState
}

/** Retour d'un enregistrement, pour un message honnête. */
enum class SaveFeedback {
    NONE,
    SAVED,
    INVALID_URL,
    MISSING_SECRET,
}

data class SettingsUiState(
    val baseUrl: String = "",
    val secretInput: String = "",
    val secretStored: Boolean = false,
    val credentialType: CredentialType = CredentialType.API_SECRET,
    val enabled: Boolean = false,
    val simulation: Boolean = false,
    val test: ConnectionTestState = ConnectionTestState.Idle,
    val feedback: SaveFeedback = SaveFeedback.NONE,
    val glooko: GlookoUiState = GlookoUiState(),
    val voice: VoiceUiState = VoiceUiState(),
    val mealRecord: MealRecordUiState = MealRecordUiState(),
) {
    /** Vrai si l'adresse et un secret sont en place (le secret peut venir d'avant). */
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && (secretStored || secretInput.isNotEmpty())
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: NightscoutConfigStore,
    private val nightscout: NightscoutRepository,
    private val glookoPreferences: GlookoPreferences,
    private val glookoScheduler: GlookoScheduler,
    private val glookoAutomation: GlookoAutomation,
    private val voiceStore: VoiceConfigStore,
    private val mealRecordStore: MealRecordConfigStore,
    private val announcer: VoiceAnnouncer,
    @VoxtralEngine private val voxtral: VoiceEngineTts,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        // Les réglages du repas changent aussi ailleurs (assistant d'installation pour Liberty,
        // restauration d'une sauvegarde) : l'écran les relit, sinon un geste ici réécrirait une
        // valeur périmée — Liberty compris.
        viewModelScope.launch {
            mealRecordStore.config.collect {
                _state.update { current -> current.copy(mealRecord = mealRecordState(current.mealRecord)) }
            }
        }
    }

    private fun initialState(): SettingsUiState {
        val config = store.config.value
        return SettingsUiState(
            baseUrl = config.baseUrl,
            secretStored = config.hasSecret,
            credentialType = config.credentialType,
            enabled = config.enabled,
            simulation = config.simulation,
            glooko = glookoState(),
            voice = voiceState(),
            mealRecord = mealRecordState(),
        )
    }

    fun onUrlChange(value: String) {
        _state.update { it.copy(baseUrl = value, feedback = SaveFeedback.NONE) }
    }

    fun onSecretChange(value: String) {
        _state.update { it.copy(secretInput = value, feedback = SaveFeedback.NONE) }
    }

    fun onCredentialTypeChange(type: CredentialType) {
        _state.update { it.copy(credentialType = type, feedback = SaveFeedback.NONE) }
    }

    /** Active ou coupe l'intégration ; enregistre tout de suite pour être prévisible. */
    fun onEnabledChange(enabled: Boolean) {
        val current = _state.value
        if (!enabled) {
            persist(current.copy(enabled = false))
            return
        }
        if (!current.isComplete) {
            _state.update {
                it.copy(feedback = SaveFeedback.MISSING_SECRET, enabled = false)
            }
            return
        }
        persist(current.copy(enabled = true))
    }

    fun onSimulationChange(simulation: Boolean) {
        _state.update { it.copy(simulation = simulation) }
        persist(_state.value)
    }

    /** Enregistre adresse, secret et type d'identifiant. */
    fun save() {
        persist(_state.value)
    }

    fun testConnection() {
        val current = _state.value
        if (!current.isComplete) {
            _state.update { it.copy(test = ConnectionTestState.Failure(NightscoutErrorKind.NOT_CONFIGURED)) }
            return
        }
        // Le test utilise les réglages enregistrés : on enregistre d'abord, sinon on
        // testerait autre chose que ce qui sera utilisé pour les envois.
        if (!persist(current)) return
        _state.update { it.copy(test = ConnectionTestState.Testing) }
        viewModelScope.launch {
            when (val result = nightscout.testConnection()) {
                is NightscoutTestResult.Success ->
                    _state.update { it.copy(test = ConnectionTestState.Success(result.server)) }
                is NightscoutTestResult.Failure ->
                    _state.update { it.copy(test = ConnectionTestState.Failure(result.kind)) }
            }
        }
    }

    /** Efface le secret du coffre et coupe l'envoi. */
    fun forgetSecret() {
        store.clearSecret()
        _state.update {
            it.copy(secretStored = false, secretInput = "", enabled = false, test = ConnectionTestState.Idle)
        }
    }

    // ---- Voix -----------------------------------------------------------------

    /**
     * Relit les réglages de voix réellement enregistrés.
     *
     * On repart du coffre plutôt que de l'état affiché : c'est la seule façon de savoir si la
     * clé existe vraiment, sans jamais en lire la valeur.
     */
    private fun voiceState(current: VoiceUiState = VoiceUiState()): VoiceUiState {
        val config = voiceStore.config.value
        return current.copy(
            engine = config.engine,
            voiceId = config.voiceId,
            consent = config.consentMistral,
            hasKey = config.hasKey,
        )
    }

    /** Enregistre le choix, puis dit ce qui manque encore pour que Voxtral parle vraiment. */
    private fun persistVoice(candidate: VoiceUiState) {
        voiceStore.save(candidate.toConfig())
        val saved = voiceState(candidate)
        val feedback = when {
            saved.engine != VoiceEngine.VOXTRAL -> VoiceFeedback.NONE
            !saved.consent -> VoiceFeedback.CONSENT_REQUIRED
            !saved.hasKey -> VoiceFeedback.MISSING_KEY
            else -> VoiceFeedback.NONE
        }
        _state.update { it.copy(voice = saved.copy(feedback = feedback)) }
    }

    fun onVoiceEngineChange(engine: VoiceEngine) {
        val current = _state.value.voice
        if (current.engine == engine) return
        persistVoice(current.copy(engine = engine, feedback = VoiceFeedback.NONE, trial = VoiceTrial.Idle))
    }

    fun onVoiceChange(voiceId: String) {
        val current = _state.value.voice
        if (current.voiceId == voiceId) return
        persistVoice(current.copy(voiceId = voiceId, trial = VoiceTrial.Idle))
    }

    /**
     * Le consentement à l'envoi du texte chez Mistral. Le couper ne révoque pas la clé, mais
     * rend Voxtral inutilisable : c'est le sens de la case.
     */
    fun onVoiceConsentChange(consent: Boolean) {
        val current = _state.value.voice
        persistVoice(current.copy(consent = consent, trial = VoiceTrial.Idle))
    }

    fun onVoiceKeyInputChange(value: String) {
        _state.update {
            it.copy(voice = it.voice.copy(keyInput = value, feedback = VoiceFeedback.NONE))
        }
    }

    /** Enregistre la clé Mistral au coffre. Un champ vide n'efface rien : il ne fait rien. */
    fun saveVoiceKey() {
        val current = _state.value.voice
        val key = current.keyInput.trim()
        if (key.isEmpty()) {
            _state.update { it.copy(voice = it.voice.copy(feedback = VoiceFeedback.MISSING_KEY)) }
            return
        }
        voiceStore.save(current.toConfig(), key)
        val saved = voiceState(current.copy(keyInput = ""))
        _state.update { it.copy(voice = saved.copy(feedback = VoiceFeedback.KEY_SAVED)) }
    }

    /** Efface la clé et revient à la voix Android, sans consentement. */
    fun clearVoiceKey() {
        voiceStore.clearKey()
        val saved = voiceState(_state.value.voice.copy(keyInput = ""))
        _state.update { it.copy(voice = saved.copy(feedback = VoiceFeedback.KEY_CLEARED)) }
    }

    /**
     * « Essai de voix » : la phrase réelle d'un repas de 40 g, avec les réglages enregistrés.
     * Le retour dit quel moteur a parlé — l'écran ne promet donc rien qu'il n'ait vérifié.
     */
    fun tryVoice() {
        if (_state.value.voice.trial is VoiceTrial.Speaking) return
        _state.update { it.copy(voice = it.voice.copy(trial = VoiceTrial.Speaking)) }
        viewModelScope.launch {
            val spokenBy = announcer.announce(MealAnnouncementText.forCarbs(TRIAL_CARBS_GRAMS))
            // Après coup seulement : `lastIssue` décrit l'essai qui vient de se terminer. Le
            // repli sur `lastSwallowed` évite le pire des cas — un échec muet, que l'écran
            // afficherait comme une simple bascule vers Android sans en donner la raison.
            val issue = when (spokenBy) {
                SpokenBy.VOXTRAL, SpokenBy.PIPER -> null
                else -> announcer.preferredIssue ?: voxtral.lastIssue ?: announcer.lastSwallowed
            }
            _state.update { it.copy(voice = it.voice.copy(trial = VoiceTrial.Done(spokenBy, issue))) }
        }
    }

    // ---- Enregistrement du repas ----------------------------------------------

    /** Relit les réglages enregistrés et le constat du service d'accessibilité CamAPS. */
    private fun mealRecordState(current: MealRecordUiState = MealRecordUiState()): MealRecordUiState {
        val config = mealRecordStore.config.value
        return current.copy(
            camapsAutoInsert = config.camapsAutoInsert,
            suiviDiabetologue = config.suiviDiabetologue,
            glookoCarnet = config.glookoCarnet,
            nightscoutNote = config.nightscoutNote,
            attachPhoto = config.attachPhoto,
            geminiDirect = config.geminiDirect,
            liberty = config.liberty,
            libertyAnswered = config.libertyAnswered,
            camapsAccessibilityRunning = CamapsAccessibilityService.isRunning,
        )
    }

    private fun persistMealRecord(candidate: MealRecordUiState) {
        mealRecordStore.save(candidate.toConfig())
        _state.update { it.copy(mealRecord = mealRecordState(candidate)) }
    }

    /**
     * Mode Liberty (équipe médicale, 24/11/2026). Toucher l'interrupteur vaut réponse à la question
     * de l'assistant d'installation.
     */
    fun onLibertyChange(value: Boolean) {
        persistMealRecord(_state.value.mealRecord.copy(liberty = value, libertyAnswered = true))
    }

    fun onCamapsAutoInsertChange(value: Boolean) {
        persistMealRecord(_state.value.mealRecord.copy(camapsAutoInsert = value))
    }

    fun onSuiviDiabetologueChange(value: Boolean) {
        persistMealRecord(_state.value.mealRecord.copy(suiviDiabetologue = value))
    }

    fun onGlookoCarnetChange(value: Boolean) {
        persistMealRecord(_state.value.mealRecord.copy(glookoCarnet = value))
    }

    fun onNightscoutNoteChange(value: Boolean) {
        persistMealRecord(_state.value.mealRecord.copy(nightscoutNote = value))
    }

    fun onAttachPhotoChange(value: Boolean) {
        persistMealRecord(_state.value.mealRecord.copy(attachPhoto = value))
    }

    fun onGeminiDirectChange(value: Boolean) {
        persistMealRecord(_state.value.mealRecord.copy(geminiDirect = value))
    }

    // ---- Import Glooko ---------------------------------------------------------

    private fun glookoState(cartographyPath: String? = null) = GlookoUiState(
        enabled = glookoPreferences.enabled,
        includeSgv = glookoPreferences.includeSgv,
        windowHours = glookoPreferences.windowHours,
        exportOnMeal = glookoPreferences.exportOnMeal,
        cadenceMinutes = glookoPreferences.cadenceMinutes,
        dryRun = glookoPreferences.dryRun,
        destination = glookoPreferences.destination,
        lastRunMs = glookoPreferences.lastRunMs,
        lastSuccessMs = glookoPreferences.lastSuccessMs,
        summary = glookoPreferences.lastSummary,
        accessibilityRunning = GlookoAccessibilityService.isRunning,
        cartographyPath = cartographyPath,
    )

    /**
     * Relit l'état réel : après un import, un changement de réglage ou un retour d'écran. En
     * profite pour reconstater le service d'accessibilité CamAPS, qu'on vient peut-être d'allumer
     * dans Android.
     */
    fun refreshGlooko() {
        _state.update {
            it.copy(
                glooko = glookoState(it.glooko.cartographyPath),
                mealRecord = it.mealRecord.copy(
                    camapsAccessibilityRunning = CamapsAccessibilityService.isRunning,
                ),
            )
        }
    }

    fun onGlookoEnabled(enabled: Boolean) {
        glookoPreferences.enabled = enabled
        glookoScheduler.apply()
        refreshGlooko()
    }

    fun onGlookoIncludeSgv(value: Boolean) {
        glookoPreferences.includeSgv = value
        refreshGlooko()
    }

    fun onGlookoWindow(hours: Long) {
        glookoPreferences.windowHours = hours
        refreshGlooko()
    }

    fun onGlookoCadence(minutes: Long) {
        glookoPreferences.cadenceMinutes = minutes
        if (glookoPreferences.enabled) glookoScheduler.schedule(minutes)
        refreshGlooko()
    }

    /** Le déclencheur normal : un export suit chaque repas annoncé. */
    fun onGlookoExportOnMeal(value: Boolean) {
        glookoPreferences.exportOnMeal = value
        refreshGlooko()
    }

    /** Nightscout classique (conversion dans l'appli) ou Nightscout for CamAPS FX (CSV intact). */
    fun onGlookoDestination(value: com.najah.mealannouncer.integration.glooko.GlookoDestination) {
        glookoPreferences.destination = value
        refreshGlooko()
    }

    fun onGlookoDryRun(value: Boolean) {
        glookoPreferences.dryRun = value
        refreshGlooko()
    }

    /** Lance un passage tout de suite ; le service avant-plan fait le reste. */
    fun syncGlookoNow() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, GlookoExportService::class.java),
        )
        refreshGlooko()
    }

    /** Ouvre les réglages d'accessibilité d'Android, là où le service se déclare. */
    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Relevé de l'écran Glooko, pour régler les libellés de l'automatisation.
     *
     * Le fichier ne contient que des libellés d'interface, jamais une donnée de santé.
     */
    fun dumpGlookoCartography() {
        viewModelScope.launch {
            val text = glookoAutomation.dump()
            val file = File(context.filesDir, "glooko-cartographie.txt")
            val path = try {
                file.writeText(text)
                file.absolutePath
            } catch (error: Exception) {
                null
            }
            _state.update { it.copy(glooko = glookoState(path)) }
        }
    }

    /**
     * Enregistre l'état courant. Renvoie `false` si l'adresse ne tient pas debout, auquel
     * cas rien n'est écrit et l'écran le dit.
     */
    private fun persist(candidate: SettingsUiState): Boolean {
        val normalized = NightscoutUrl.normalize(candidate.baseUrl)
        if (normalized == null) {
            _state.update { it.copy(feedback = SaveFeedback.INVALID_URL) }
            return false
        }
        if (candidate.enabled && !candidate.secretStored && candidate.secretInput.isEmpty()) {
            _state.update { it.copy(feedback = SaveFeedback.MISSING_SECRET) }
            return false
        }
        store.save(
            config = NightscoutConfig(
                baseUrl = normalized,
                credentialType = candidate.credentialType,
                enabled = candidate.enabled,
                // La simulation ne prend effet qu'une fois l'envoi activé : sinon elle ne
                // simulerait rien du tout.
                simulation = candidate.simulation && candidate.enabled,
            ),
            secret = candidate.secretInput.ifEmpty { null },
        )
        _state.update {
            it.copy(
                baseUrl = normalized,
                secretInput = "",
                secretStored = true,
                simulation = candidate.simulation && candidate.enabled,
                feedback = SaveFeedback.SAVED,
            )
        }
        return true
    }

    private companion object {

        /** Glucides de la phrase d'essai : un repas ordinaire, ni extrême ni ambigu. */
        const val TRIAL_CARBS_GRAMS = 40.0
    }
}
