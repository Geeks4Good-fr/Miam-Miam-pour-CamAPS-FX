package com.najah.mealannouncer.feature.settings

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import com.najah.mealannouncer.core.datastore.AiSecretStore
import com.najah.mealannouncer.integration.camaps.CamapsAccessibilityService
import com.najah.mealannouncer.integration.glookoexport.GlookoAccessibilityService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * État réel d'un service d'accessibilité Miam-Miam.
 *
 * Après une mise à jour d'APK ou un arrêt forcé, Android laisse souvent le service coché dans
 * `enabled_accessibility_services` sans le relancer ([UNBOUND_AFTER_UPDATE]). L'assistant le
 * détecte pour demander à l'utilisateur de le décocher puis recocher.
 */
enum class AccessibilityBindingState {
    OFF,
    UNBOUND_AFTER_UPDATE,
    BOUND_AND_RUNNING,
}

object AccessibilityDiagnostics {
    fun inspect(
        enabledServicesSetting: String?,
        packageName: String,
        serviceClassName: String,
        isRunning: Boolean,
    ): AccessibilityBindingState {
        if (isRunning) return AccessibilityBindingState.BOUND_AND_RUNNING
        if (enabledServicesSetting.isNullOrBlank()) return AccessibilityBindingState.OFF
        val fullFlat = "$packageName/$serviceClassName"
        val shortFlat = if (serviceClassName.startsWith("$packageName.")) {
            "$packageName/${serviceClassName.removePrefix(packageName)}"
        } else {
            fullFlat
        }
        val entries = enabledServicesSetting.split(':').map { it.trim() }
        val enabledInSettings = entries.any { entry ->
            entry.equals(fullFlat, ignoreCase = true) ||
                entry.equals(shortFlat, ignoreCase = true) ||
                entry.endsWith("/$serviceClassName", ignoreCase = true)
        }
        return if (enabledInSettings) {
            AccessibilityBindingState.UNBOUND_AFTER_UPDATE
        } else {
            AccessibilityBindingState.OFF
        }
    }
}

sealed interface WizardPackTaskState {
    data object Idle : WizardPackTaskState
    data class Running(
        val pack: OfflinePack,
        val progress: PackInstallProgress,
        val stepIndex: Int,
        val totalSteps: Int,
    ) : WizardPackTaskState

    data class Completed(val message: String) : WizardPackTaskState
    data class Error(val message: String) : WizardPackTaskState
}

data class SetupWizardUiState(
    val hasAiKey: Boolean = false,
    val installedPacks: Map<OfflinePack, Boolean> = emptyMap(),
    val packTask: WizardPackTaskState = WizardPackTaskState.Idle,
    val hasMicPermission: Boolean = false,
    val hasNotificationPermission: Boolean = true,
    val camapsBinding: AccessibilityBindingState = AccessibilityBindingState.OFF,
    val glookoBinding: AccessibilityBindingState = AccessibilityBindingState.OFF,
    val requiresRestrictedSettingsGuide: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
) {
    val allRequiredPacksInstalled: Boolean
        get() = OfflinePack.entries.filter { it.required }.all { installedPacks[it] == true }

    val hasUnboundAccessibilityTrap: Boolean
        get() = camapsBinding == AccessibilityBindingState.UNBOUND_AFTER_UPDATE ||
            glookoBinding == AccessibilityBindingState.UNBOUND_AFTER_UPDATE

    val essentialStepsCompletedCount: Int
        get() = listOf(
            hasAiKey,
            allRequiredPacksInstalled,
            hasMicPermission && hasNotificationPermission,
        ).count { it }

    val isEssentialSetupComplete: Boolean
        get() = hasAiKey && allRequiredPacksInstalled && hasMicPermission
}

@HiltViewModel
class SetupWizardViewModel @Inject constructor(
    private val installer: OfflinePacksInstaller,
    private val aiSecrets: AiSecretStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var activeJob: Job? = null

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<SetupWizardUiState> = _state.asStateFlow()

    /**
     * Vrai si l'assistant doit s'ouvrir de lui-même au lancement : tant qu'il manque la clé IA ou
     * l'un des trois paquets hors ligne indispensables, et que l'utilisateur n'a pas touché
     * « Plus tard ».
     */
    fun shouldOpenAtStartup(): Boolean {
        if (prefs.getBoolean(KEY_DISMISSED, false)) return false
        val current = readState()
        return !current.hasAiKey || !current.allRequiredPacksInstalled
    }

    fun dismissStartup() {
        prefs.edit().putBoolean(KEY_DISMISSED, true).apply()
    }

    fun refresh() {
        val updated = readState()
        _state.update { prev ->
            updated.copy(packTask = prev.packTask)
        }
    }

    private fun readState(): SetupWizardUiState {
        val packsMap = OfflinePack.entries.associateWith { installer.isInstalled(it) }
        val enabledServices = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull()
        val pkg = context.packageName
        val camapsState = AccessibilityDiagnostics.inspect(
            enabledServicesSetting = enabledServices,
            packageName = pkg,
            serviceClassName = CamapsAccessibilityService::class.java.name,
            isRunning = CamapsAccessibilityService.isRunning,
        )
        val glookoState = AccessibilityDiagnostics.inspect(
            enabledServicesSetting = enabledServices,
            packageName = pkg,
            serviceClassName = GlookoAccessibilityService::class.java.name,
            isRunning = GlookoAccessibilityService.isRunning,
        )
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val hasNotif = NotificationManagerCompat.from(context).areNotificationsEnabled()

        return SetupWizardUiState(
            hasAiKey = aiSecrets.hasSecret(),
            installedPacks = packsMap,
            hasMicPermission = hasMic,
            hasNotificationPermission = hasNotif,
            camapsBinding = camapsState,
            glookoBinding = glookoState,
            requiresRestrictedSettingsGuide = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        )
    }

    /** Télécharge et installe à la suite tous les paquets indispensables encore absents. */
    fun installAllRequired() {
        if (activeJob?.isActive == true) return
        val missing = OfflinePack.entries.filter { it.required && !installer.isInstalled(it) }
        val queue = if (missing.isEmpty()) OfflinePack.entries.filter { it.required } else missing
        runInstallQueue(queue)
    }

    /** Télécharge et installe un paquet précis (ex. Piper ou mise à jour d'Open Food Facts). */
    fun installPack(pack: OfflinePack) {
        if (activeJob?.isActive == true) return
        runInstallQueue(listOf(pack))
    }

    /** Installe un paquet depuis une archive `.zip` présente sur le téléphone. */
    fun importPackFromUri(pack: OfflinePack, uri: Uri) {
        if (activeJob?.isActive == true) return
        activeJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    packTask = WizardPackTaskState.Running(
                        pack = pack,
                        progress = PackInstallProgress.Downloading(pack, 0L, pack.zipBytes),
                        stepIndex = 1,
                        totalSteps = 1,
                    ),
                )
            }
            val result = installer.installFromUri(pack, uri) { progress ->
                _state.update {
                    it.copy(
                        packTask = WizardPackTaskState.Running(
                            pack = pack,
                            progress = progress,
                            stepIndex = 1,
                            totalSteps = 1,
                        ),
                    )
                }
            }
            val refreshed = readState()
            _state.update {
                refreshed.copy(
                    packTask = result.fold(
                        onSuccess = { WizardPackTaskState.Completed("« ${pack.title} » est installé et prêt.") },
                        onFailure = { err -> WizardPackTaskState.Error(err.message ?: "Échec de l'import.") },
                    ),
                )
            }
        }
    }

    fun cancelInstall() {
        activeJob?.cancel()
        activeJob = null
        val refreshed = readState()
        _state.update {
            refreshed.copy(packTask = WizardPackTaskState.Idle)
        }
    }

    private fun runInstallQueue(queue: List<OfflinePack>) {
        activeJob = viewModelScope.launch {
            for ((index, pack) in queue.withIndex()) {
                val step = index + 1
                _state.update {
                    it.copy(
                        packTask = WizardPackTaskState.Running(
                            pack = pack,
                            progress = PackInstallProgress.Downloading(pack, 0L, pack.zipBytes),
                            stepIndex = step,
                            totalSteps = queue.size,
                        ),
                    )
                }
                val res = installer.downloadAndInstall(pack) { progress ->
                    _state.update {
                        it.copy(
                            packTask = WizardPackTaskState.Running(
                                pack = pack,
                                progress = progress,
                                stepIndex = step,
                                totalSteps = queue.size,
                            ),
                        )
                    }
                }
                if (res.isFailure) {
                    val err = res.exceptionOrNull()?.message ?: "Téléchargement interrompu"
                    val refreshed = readState()
                    _state.update {
                        refreshed.copy(
                            packTask = WizardPackTaskState.Error("${pack.title} : $err"),
                        )
                    }
                    return@launch
                }
            }
            val refreshed = readState()
            _state.update {
                refreshed.copy(
                    packTask = WizardPackTaskState.Completed(
                        "Tous les fichiers hors ligne sélectionnés sont installés et vérifiés.",
                    ),
                )
            }
        }
    }

    fun openAppDetailsSettings() {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun openAccessibilitySettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun openNotificationSettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private companion object {
        const val PREFS_NAME = "mealannouncer-setup-wizard"
        const val KEY_DISMISSED = "setup_dismissed"
    }
}

/**
 * Carte affichée tout en haut de l'écran Réglages : donne l'état global de l'installation et
 * permet d'ouvrir l'assistant pas-à-pas en un toucher.
 */
@Composable
fun SetupWizardBannerCard(
    state: SetupWizardUiState,
    onOpenWizard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (state.isEssentialSetupComplete && !state.hasUnboundAccessibilityTrap) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Assistant d'installation simplifiée",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            val summary = when {
                state.hasUnboundAccessibilityTrap ->
                    "Attention : un service d'accessibilité (CamAPS ou Glooko) a été décroché par Android après une mise à jour. Ouvrez l'assistant pour le réactiver."
                state.isEssentialSetupComplete ->
                    "Installation prête (${state.essentialStepsCompletedCount}/3 étapes essentielles validées). Vous pouvez gérer ou mettre à jour les modèles hors ligne ici."
                else ->
                    "Configuration en cours (${state.essentialStepsCompletedCount}/3 étapes essentielles). Téléchargez les modèles hors ligne et reliez OpenRouter sans ordinateur."
            }
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onOpenWizard,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.isEssentialSetupComplete && !state.hasUnboundAccessibilityTrap) {
                        "Vérifier l'installation et les fichiers hors ligne"
                    } else {
                        "Ouvrir l'assistant d'installation (4 étapes)"
                    },
                )
            }
        }
    }
}

/**
 * Écran complet de l'assistant d'installation en 4 étapes pour un utilisateur non technicien.
 */
@Composable
fun SetupWizardScreen(
    wizardState: SetupWizardUiState,
    aiKeyState: AiKeyUiState,
    aiKeyActions: AiKeyActions,
    onInstallAllRequired: () -> Unit,
    onInstallPack: (OfflinePack) -> Unit,
    onImportPackZip: (OfflinePack) -> Unit,
    onCancelInstall: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRefresh: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LifecycleResumeEffect(Unit) {
        onRefresh()
        onPauseOrDispose { }
    }

    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Installation pas à pas",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            TextButton(onClick = onFinish) {
                Text(if (wizardState.isEssentialSetupComplete) "Terminer" else "Plus tard")
            }
        }

        Text(
            text = "Cet assistant prépare votre téléphone sans ordinateur ni ligne de commande : " +
                "${wizardState.essentialStepsCompletedCount} sur 3 étapes indispensables sont prêtes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- ÉTAPE 1 : Clé OpenRouter ------------------------------------------------
        StepCard(
            stepNumber = 1,
            title = "Intelligence artificielle (OpenRouter)",
            ready = aiKeyState.stored,
            badgeText = if (aiKeyState.stored) "Prêt" else "À relier",
        ) {
            Text(
                text = "Une seule connexion permet de calculer les repas, lire les photos d'assiette ou d'emballage et faire relire chaque calcul.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { aiKeyActions.onConnectOAuth(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (aiKeyState.stored) {
                        "Reconnecter un compte OpenRouter en 1 clic"
                    } else {
                        "Se connecter à OpenRouter en 1 clic"
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Ou collez directement une clé sk-or-v1-… dans la section ci-dessous :",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            AiKeySettingsSection(state = aiKeyState, actions = aiKeyActions)
        }

        // ---- ÉTAPE 2 : Fichiers hors ligne -------------------------------------------
        StepCard(
            stepNumber = 2,
            title = "Bases et voix hors ligne (sans PC)",
            ready = wizardState.allRequiredPacksInstalled,
            badgeText = if (wizardState.allRequiredPacksInstalled) "Installés" else "À télécharger",
        ) {
            Text(
                text = "L'application télécharge et installe elle-même la dictée locale, le mot-clé « Miam-miam » et les 3,1 millions de produits d'Open Food Facts. Conseil : connectez-vous en Wi-Fi (~420 Mo au total).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))

            val task = wizardState.packTask
            val isRunning = task is WizardPackTaskState.Running

            if (!wizardState.allRequiredPacksInstalled && !isRunning) {
                Button(
                    onClick = onInstallAllRequired,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Tout télécharger et installer automatiquement")
                }
                Spacer(Modifier.height(8.dp))
            }

            if (task is WizardPackTaskState.Running) {
                PackProgressPanel(task = task, onCancel = onCancelInstall)
                Spacer(Modifier.height(12.dp))
            } else if (task is WizardPackTaskState.Completed) {
                Text(
                    text = task.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
            } else if (task is WizardPackTaskState.Error) {
                Text(
                    text = task.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
            }

            for (pack in OfflinePack.entries) {
                val installed = wizardState.installedPacks[pack] == true
                OfflinePackRow(
                    pack = pack,
                    installed = installed,
                    busy = isRunning,
                    onDownload = { onInstallPack(pack) },
                    onImportZip = { onImportPackZip(pack) },
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        // ---- ÉTAPE 3 : Microphone et notifications -----------------------------------
        val permsReady = wizardState.hasMicPermission && wizardState.hasNotificationPermission
        StepCard(
            stepNumber = 3,
            title = "Microphone et notifications",
            ready = permsReady,
            badgeText = if (permsReady) "Autorisés" else "À autoriser",
        ) {
            Text(
                text = "Le microphone sert à dicter votre repas ; les notifications permettent d'afficher le suivi et d'écouter « Miam-miam ».",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (wizardState.hasMicPermission) {
                        "Microphone : autorisé"
                    } else {
                        "Microphone : non autorisé"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (wizardState.hasMicPermission) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.weight(1f),
                )
                if (!wizardState.hasMicPermission) {
                    Button(onClick = onRequestMicPermission) {
                        Text("Autoriser le micro")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (wizardState.hasNotificationPermission) {
                        "Notifications : autorisées"
                    } else {
                        "Notifications : coupées"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (wizardState.hasNotificationPermission) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.weight(1f),
                )
                if (!wizardState.hasNotificationPermission) {
                    OutlinedButton(onClick = onOpenNotifications) {
                        Text("Autoriser")
                    }
                }
            }
        }

        // ---- ÉTAPE 4 : Services d'accessibilité CamAPS & Glooko ----------------------
        val bothBound = wizardState.camapsBinding == AccessibilityBindingState.BOUND_AND_RUNNING &&
            wizardState.glookoBinding == AccessibilityBindingState.BOUND_AND_RUNNING
        val step4Badge = when {
            wizardState.hasUnboundAccessibilityTrap -> "À relancer"
            bothBound -> "Actifs"
            wizardState.camapsBinding == AccessibilityBindingState.BOUND_AND_RUNNING -> "CamAPS actif"
            else -> "Optionnel"
        }
        StepCard(
            stepNumber = 4,
            title = "Saisie automatique CamAPS FX & Glooko",
            ready = bothBound,
            badgeText = step4Badge,
        ) {
            Text(
                text = "Pour que Miam-Miam pré-remplisse « Ajouter un repas » dans CamAPS FX (en vous laissant toujours appuyer sur Confirmer) et écrive la note dans le Carnet Glooko, activez ses deux services d'accessibilité.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))

            AccessibilityServiceStatusRow(
                label = "Insertion repas CamAPS (Miam-Miam)",
                binding = wizardState.camapsBinding,
            )
            Spacer(Modifier.height(6.dp))
            AccessibilityServiceStatusRow(
                label = "Export Glooko (Miam-Miam)",
                binding = wizardState.glookoBinding,
            )

            if (wizardState.requiresRestrictedSettingsGuide && !bothBound) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Astuce Android 13+ (« Paramètres restreints »)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Si Android grise le service dans Accessibilité :\n" +
                                "1. Touchez « 1. Ouvrir la fiche de l'appli », puis le menu ⋮ en haut à droite → « Autoriser les paramètres restreints ».\n" +
                                "2. Touchez « 2. Ouvrir l'Accessibilité » pour activer les services.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (wizardState.requiresRestrictedSettingsGuide && !bothBound) {
                    OutlinedButton(
                        onClick = onOpenAppDetails,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("1. Ouvrir la fiche de l'appli (Paramètres restreints)")
                    }
                }
                Button(
                    onClick = onOpenAccessibility,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (wizardState.requiresRestrictedSettingsGuide && !bothBound) {
                            "2. Ouvrir les réglages d'Accessibilité Android"
                        } else {
                            "Ouvrir les réglages d'Accessibilité Android"
                        },
                    )
                }
            }
        }

        Button(
            onClick = onFinish,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text("Terminer et revenir à Miam-Miam")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StepCard(
    stepNumber: Int,
    title: String,
    ready: Boolean,
    badgeText: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Étape $stepNumber · $title",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (ready) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    },
                ) {
                    Text(
                        text = if (ready) "✓ $badgeText" else badgeText,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun PackProgressPanel(
    task: WizardPackTaskState.Running,
    onCancel: () -> Unit,
) {
    val progress = task.progress
    val (label, fraction) = when (progress) {
        is PackInstallProgress.Downloading -> {
            val dlMb = progress.downloadedBytes / 1_048_576f
            val totalMb = (progress.totalBytes.takeIf { it > 0L } ?: task.pack.zipBytes) / 1_048_576f
            val ratio = if (totalMb > 0f) (dlMb / totalMb).coerceIn(0f, 1f) else 0f
            val text = String.format(
                Locale.FRANCE,
                "Téléchargement (%d/%d) : %s — %.1f / %.1f Mo (%.0f %%)",
                task.stepIndex,
                task.totalSteps,
                task.pack.title,
                dlMb,
                totalMb,
                ratio * 100f,
            )
            text to ratio
        }
        is PackInstallProgress.Verifying -> {
            "Vérification de l'empreinte SHA-256 de ${task.pack.archiveName}…" to 1f
        }
        is PackInstallProgress.Extracting -> {
            val exMb = progress.extractedBytes / 1_048_576f
            val totalMb = (progress.totalUncompressedBytes.takeIf { it > 0L } ?: task.pack.uncompressedBytes) / 1_048_576f
            val ratio = if (totalMb > 0f) (exMb / totalMb).coerceIn(0f, 1f) else 0f
            val text = String.format(
                Locale.FRANCE,
                "Décompression (%d/%d) : %s — %.0f / %.0f Mo",
                task.stepIndex,
                task.totalSteps,
                task.pack.title,
                exMb,
                totalMb,
            )
            text to ratio
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onCancel) {
            Text("Annuler le téléchargement")
        }
    }
}

@Composable
private fun OfflinePackRow(
    pack: OfflinePack,
    installed: Boolean,
    busy: Boolean,
    onDownload: () -> Unit,
    onImportZip: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = pack.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (installed) {
                        "✓ Installé"
                    } else {
                        String.format(Locale.FRANCE, "%.0f Mo", pack.zipBytes / 1_048_576f)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (installed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = pack.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDownload,
                    enabled = !busy,
                ) {
                    Text(if (installed) "Réinstaller" else "Télécharger")
                }
                TextButton(
                    onClick = onImportZip,
                    enabled = !busy,
                ) {
                    Text("Choisir un .zip local")
                }
            }
        }
    }
}

@Composable
private fun AccessibilityServiceStatusRow(
    label: String,
    binding: AccessibilityBindingState,
) {
    val (statusText, color) = when (binding) {
        AccessibilityBindingState.BOUND_AND_RUNNING ->
            "✓ Actif et relié" to MaterialTheme.colorScheme.primary
        AccessibilityBindingState.UNBOUND_AFTER_UPDATE ->
            "⚠️ Coché dans Android mais décroché (après mise à jour) : décochez-le puis recochez-le dans Accessibilité." to MaterialTheme.colorScheme.error
        AccessibilityBindingState.OFF ->
            "Non activé" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(text = statusText, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
