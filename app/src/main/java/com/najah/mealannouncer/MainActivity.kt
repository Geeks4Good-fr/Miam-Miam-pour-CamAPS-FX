package com.najah.mealannouncer

import android.graphics.Color as AndroidColor
import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.najah.mealannouncer.feature.assistant.MealAssistantScreen
import com.najah.mealannouncer.feature.assistant.MealAssistantViewModel
import com.najah.mealannouncer.feature.assistant.WakeWordLink
import com.najah.mealannouncer.feature.confirmation.ConfirmationPayload
import com.najah.mealannouncer.feature.confirmation.ConfirmationScreen
import com.najah.mealannouncer.feature.confirmation.ConfirmationViewModel
import com.najah.mealannouncer.feature.history.HistoryScreen
import com.najah.mealannouncer.feature.history.HistoryViewModel
import com.najah.mealannouncer.feature.meal.HomeScreen
import com.najah.mealannouncer.feature.meal.ManualEntryScreen
import com.najah.mealannouncer.feature.meal.ManualEntryViewModel
import com.najah.mealannouncer.core.datastore.HomeVoiceConfigStore
import com.najah.mealannouncer.core.datastore.WakeWordConfigStore
import com.najah.mealannouncer.feature.settings.GlookoActions
import com.najah.mealannouncer.feature.settings.HomeVoiceActions
import com.najah.mealannouncer.feature.settings.HomeVoiceSettingsViewModel
import com.najah.mealannouncer.feature.settings.MealRecordActions
import com.najah.mealannouncer.feature.settings.SettingsScreen
import com.najah.mealannouncer.feature.settings.SettingsViewModel
import com.najah.mealannouncer.feature.settings.VoiceActions
import com.najah.mealannouncer.feature.settings.WakeWordActions
import com.najah.mealannouncer.feature.settings.WakeWordSettingsViewModel
import com.najah.mealannouncer.feature.settings.AiKeySettingsViewModel
import com.najah.mealannouncer.feature.settings.PortionsActions
import com.najah.mealannouncer.feature.settings.PortionsSettingsViewModel
import com.najah.mealannouncer.feature.settings.ShoppingActions
import com.najah.mealannouncer.feature.settings.ShoppingSettingsViewModel
import com.najah.mealannouncer.home.HomeLinkService
import com.najah.mealannouncer.ui.theme.MealAnnouncerTheme
import com.najah.mealannouncer.ui.theme.PaperBackground
import com.najah.mealannouncer.wake.WakeWordService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Écran unique de l'application et navigation entre ses étapes.
 *
 * La navigation tient dans un seul état de type texte : cinq écrans ne justifient pas une
 * bibliothèque de navigation, et cet état survit à la rotation grâce à [rememberSaveable].
 * Les ViewModels portent les données qui, elles, ne doivent pas être perdues.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val manualEntryViewModel: ManualEntryViewModel by viewModels()
    private val assistantViewModel: MealAssistantViewModel by viewModels()
    private val confirmationViewModel: ConfirmationViewModel by viewModels()
    private val historyViewModel: HistoryViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val homeVoiceViewModel: HomeVoiceSettingsViewModel by viewModels()
    private val wakeWordViewModel: WakeWordSettingsViewModel by viewModels()
    private val aiKeyViewModel: AiKeySettingsViewModel by viewModels()
    private val portionsViewModel: PortionsSettingsViewModel by viewModels()
    private val shoppingViewModel: ShoppingSettingsViewModel by viewModels()

    @Inject
    lateinit var homeVoice: HomeVoiceConfigStore

    @Inject
    lateinit var wakeWord: WakeWordConfigStore

    @Inject
    lateinit var wakeWordLink: WakeWordLink

    /** Chaque repas dicté à une enceinte ou déclenché par « miam-miam » incrémente ce compteur : l'écran du repas s'ouvre. */
    private val homeMealRequests = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Le papier aquarelle est toujours clair : icônes foncées dans les barres du système,
        // même quand le téléphone est en mode sombre.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        handleIncomingMealIntent(intent)
        // Le service de la maison suit l'interrupteur des réglages. Il est (re)lancé ici, appli au
        // premier plan : Android refuse de démarrer un service au premier plan depuis l'arrière-plan.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                homeVoice.config.collect { HomeLinkService.sync(this@MainActivity, it.enabled) }
            }
        }
        // Même règle pour le service d'écoute « miam-miam » sur le téléphone.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                wakeWord.config.collect { WakeWordService.sync(this@MainActivity, it.enabled) }
            }
        }
        // Si « miam-miam » est détecté alors que l'activité est déjà ouverte sur un autre écran,
        // bascule immédiatement sur l'écran du repas.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                wakeWordLink.pending.collect { pending ->
                    if (pending != null) {
                        setShowWhenLocked(true)
                        setTurnScreenOn(true)
                        homeMealRequests.intValue++
                    }
                }
            }
        }
        // Un repas de la maison doit aller dans CamAPS : on présente le code ou l'empreinte, plutôt
        // que de laisser l'écran du repas par-dessus un téléphone encore verrouillé.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                assistantViewModel.unlockWanted.collect { wanted ->
                    if (wanted) getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this@MainActivity, null)
                }
            }
        }
        setContent {
            MealAnnouncerTheme {
                AppRoot(
                    manualEntryViewModel = manualEntryViewModel,
                    assistantViewModel = assistantViewModel,
                    confirmationViewModel = confirmationViewModel,
                    historyViewModel = historyViewModel,
                    settingsViewModel = settingsViewModel,
                    homeVoiceViewModel = homeVoiceViewModel,
                    wakeWordViewModel = wakeWordViewModel,
                    aiKeyViewModel = aiKeyViewModel,
                    portionsViewModel = portionsViewModel,
                    shoppingViewModel = shoppingViewModel,
                    homeMealRequests = homeMealRequests.intValue,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingMealIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        // L'écran du repas ne reste visible par-dessus le verrouillage que le temps de ce repas.
        setShowWhenLocked(false)
        setTurnScreenOn(false)
    }

    /**
     * Un repas dicté à une enceinte ou déclenché par « miam-miam » : l'écran s'ouvre par-dessus le
     * verrouillage.
     */
    private fun handleIncomingMealIntent(intent: Intent?) {
        val action = intent?.action
        if (action != HomeLinkService.ACTION_HOME_MEAL && action != WakeWordService.ACTION_WAKE_WORD) return
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        homeMealRequests.intValue++
    }
}

private const val ROUTE_HOME = "home"
private const val ROUTE_MANUAL = "manual"
private const val ROUTE_ASSISTANT = "assistant"
private const val ROUTE_CONFIRMATION = "confirmation"
private const val ROUTE_HISTORY = "history"
private const val ROUTE_SETTINGS = "settings"

@Composable
private fun AppRoot(
    manualEntryViewModel: ManualEntryViewModel,
    assistantViewModel: MealAssistantViewModel,
    confirmationViewModel: ConfirmationViewModel,
    historyViewModel: HistoryViewModel,
    settingsViewModel: SettingsViewModel,
    homeVoiceViewModel: HomeVoiceSettingsViewModel,
    wakeWordViewModel: WakeWordSettingsViewModel,
    aiKeyViewModel: AiKeySettingsViewModel,
    portionsViewModel: PortionsSettingsViewModel,
    shoppingViewModel: ShoppingSettingsViewModel,
    homeMealRequests: Int,
) {
    var route by rememberSaveable { mutableStateOf(ROUTE_HOME) }
    LaunchedEffect(homeMealRequests) {
        if (homeMealRequests > 0) route = ROUTE_ASSISTANT
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val unavailableMessage = stringResource(R.string.home_announce_unavailable)
    val showUnavailable: () -> Unit = {
        scope.launch { snackbarHostState.showSnackbar(unavailableMessage) }
    }

    // Papier aquarelle et fruits peints derrière tous les écrans (thème du 10/11/2026).
    PaperBackground(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            when (route) {
                ROUTE_ASSISTANT -> {
                    val state by assistantViewModel.state.collectAsStateWithLifecycle()
                    val context = LocalContext.current
                    // L'autorisation n'est demandée qu'au premier appui sur le micro : rien
                    // n'est sollicité tant que l'utilisateur n'a pas exprimé ce besoin.
                    var asked by remember { mutableStateOf(false) }
                    val permission = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { granted ->
                        asked = false
                        if (granted) {
                            assistantViewModel.onMicToggled()
                        } else {
                            assistantViewModel.onMicDenied()
                        }
                    }
                    // Photo d'assiette : l'utilisateur choisit la source. La prise de vue écrit
                    // dans le cache de l'application (jamais dans la galerie) ; la photo choisie
                    // dans le téléphone n'est ni copiée ni modifiée.
                    var photoTarget by remember { mutableStateOf<Uri?>(null) }
                    val takePicture = rememberLauncherForActivityResult(
                        ActivityResultContracts.TakePicture(),
                    ) { taken ->
                        val target = photoTarget
                        photoTarget = null
                        if (taken && target != null) {
                            assistantViewModel.onPhotoCaptured(target)
                        } else {
                            assistantViewModel.onPhotoCancelled(target)
                        }
                    }
                    val pickPicture = rememberLauncherForActivityResult(
                        ActivityResultContracts.PickVisualMedia(),
                    ) { picked ->
                        if (picked != null) {
                            assistantViewModel.onPhotoPicked(picked)
                        } else {
                            assistantViewModel.onPhotoCancelled(null)
                        }
                    }
                    val leave = {
                        assistantViewModel.stopRecording()
                        route = ROUTE_HOME
                    }
                    BackHandler { leave() }
                    MealAssistantScreen(
                        state = state,
                        onMic = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                assistantViewModel.onMicToggled()
                            } else if (!asked) {
                                asked = true
                                permission.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                assistantViewModel.onMicDenied()
                            }
                        },
                        onTranscriptChange = assistantViewModel::onTranscriptChange,
                        onAnalyse = assistantViewModel::analyseTranscript,
                        onQuantityChange = assistantViewModel::onQuantityChange,
                        onUnitChange = assistantViewModel::onUnitChange,
                        onAnnounce = assistantViewModel::announceTotal,
                        onWriteCamaps = assistantViewModel::writeToCamaps,
                        onStartDialogue = assistantViewModel::startPortionDialogue,
                        onStopDialogue = assistantViewModel::stopDialogue,
                        onStopCamapsConfirm = assistantViewModel::stopCamapsConfirm,
                        onStopConversation = assistantViewModel::stopConversation,
                        onRejectProduct = assistantViewModel::rejectProduct,
                        onTakePhoto = {
                            val target = assistantViewModel.newPhotoTarget()
                            photoTarget = target
                            try {
                                takePicture.launch(target)
                            } catch (error: ActivityNotFoundException) {
                                // Aucun appareil photo utilisable : on efface le fichier vide et
                                // on le dit, plutôt que de laisser un bouton sans effet.
                                assistantViewModel.onCameraUnavailable(target)
                            }
                        },
                        onPortionPick = assistantViewModel::pickPortion,
                        onPreciseByPhoto = { lineId ->
                            val target = assistantViewModel.newPrecisionPhotoTarget(lineId)
                            photoTarget = target
                            try {
                                takePicture.launch(target)
                            } catch (error: ActivityNotFoundException) {
                                assistantViewModel.onCameraUnavailable(target)
                            }
                        },
                        onPickPhoto = {
                            pickPicture.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        onConfirmPhoto = assistantViewModel::confirmPhoto,
                        onDiscardPhoto = assistantViewModel::discardPhoto,
                        onReset = assistantViewModel::reset,
                        onBack = leave,
                        modifier = Modifier.padding(innerPadding),
                    )
                }

                ROUTE_MANUAL -> {
                    val state by manualEntryViewModel.state.collectAsStateWithLifecycle()
                    BackHandler { route = ROUTE_HOME }
                    ManualEntryScreen(
                        state = state,
                        onBack = { route = ROUTE_HOME },
                        onQueryChange = manualEntryViewModel::onQueryChange,
                        onSearchLocal = manualEntryViewModel::searchLocal,
                        onSearchOnline = manualEntryViewModel::searchOnline,
                        onAddProduct = manualEntryViewModel::addProduct,
                        onQuantityChange = manualEntryViewModel::onQuantityChange,
                        onUnitChange = manualEntryViewModel::onUnitChange,
                        onRemoveLine = manualEntryViewModel::removeLine,
                        onContinue = {
                            val confirmable = manualEntryViewModel.confirmableMeal()
                            if (confirmable != null) {
                                confirmationViewModel.prepare(
                                    ConfirmationPayload(confirmable.meal, confirmable.skipped),
                                )
                                route = ROUTE_CONFIRMATION
                            }
                        },
                        modifier = Modifier.padding(innerPadding),
                    )
                }

                ROUTE_CONFIRMATION -> {
                    val payload by confirmationViewModel.payload.collectAsStateWithLifecycle()
                    val confirmationState by confirmationViewModel.state.collectAsStateWithLifecycle()
                    val nightscoutNotice by
                        confirmationViewModel.nightscoutNotice.collectAsStateWithLifecycle()
                    val glookoTrigger by
                        confirmationViewModel.glookoTrigger.collectAsStateWithLifecycle()
                    BackHandler { route = ROUTE_MANUAL }
                    ConfirmationScreen(
                        payload = payload,
                        state = confirmationState,
                        nightscoutNotice = nightscoutNotice,
                        glookoTrigger = glookoTrigger,
                        onSave = confirmationViewModel::save,
                        onBack = { route = ROUTE_MANUAL },
                        onOpenHistory = { route = ROUTE_HISTORY },
                        onNewMeal = {
                            confirmationViewModel.clear()
                            manualEntryViewModel.clearMeal()
                            route = ROUTE_MANUAL
                        },
                        modifier = Modifier.padding(innerPadding),
                    )
                }

                ROUTE_HISTORY -> {
                    val entries by historyViewModel.entries.collectAsStateWithLifecycle()
                    BackHandler { route = ROUTE_HOME }
                    HistoryScreen(
                        entries = entries,
                        onBack = { route = ROUTE_HOME },
                        onDelete = historyViewModel::delete,
                        modifier = Modifier.padding(innerPadding),
                    )
                }

                ROUTE_SETTINGS -> {
                    val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
                    val homeVoiceState by homeVoiceViewModel.state.collectAsStateWithLifecycle()
                    val wakeWordState by wakeWordViewModel.state.collectAsStateWithLifecycle()
                    val aiKeyState by aiKeyViewModel.state.collectAsStateWithLifecycle()
                    val portionsState by portionsViewModel.state.collectAsStateWithLifecycle()
                    val shoppingState by shoppingViewModel.state.collectAsStateWithLifecycle()
                    // Paquets de portions : le sélecteur de fichiers d'Android, sans permission de stockage.
                    val importPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        uri?.let(portionsViewModel::import)
                    }
                    val exportPack = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                        uri?.let(portionsViewModel::export)
                    }
                    val importReceiptDevice = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        uri?.let(shoppingViewModel::importReceipt)
                    }
                    val importReceiptDrive = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                        result.data?.data?.let(shoppingViewModel::importReceipt)
                    }
                    val context = LocalContext.current
                    val micPermission = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { granted ->
                        wakeWordViewModel.refresh()
                        if (granted) wakeWordViewModel.onEnabledChange(true)
                    }
                    BackHandler { route = ROUTE_HOME }
                    SettingsScreen(
                        state = settingsState,
                        onUrlChange = settingsViewModel::onUrlChange,
                        onSecretChange = settingsViewModel::onSecretChange,
                        onCredentialTypeChange = settingsViewModel::onCredentialTypeChange,
                        onEnabledChange = settingsViewModel::onEnabledChange,
                        onSimulationChange = settingsViewModel::onSimulationChange,
                        onSave = settingsViewModel::save,
                        onTest = settingsViewModel::testConnection,
                        onForgetSecret = settingsViewModel::forgetSecret,
                        glookoActions = GlookoActions(
                            onEnabled = settingsViewModel::onGlookoEnabled,
                            onIncludeSgv = settingsViewModel::onGlookoIncludeSgv,
                            onWindow = settingsViewModel::onGlookoWindow,
                            onExportOnMeal = settingsViewModel::onGlookoExportOnMeal,
                            onCadence = settingsViewModel::onGlookoCadence,
                            onDryRun = settingsViewModel::onGlookoDryRun,
                            onDestination = settingsViewModel::onGlookoDestination,
                            onSyncNow = settingsViewModel::syncGlookoNow,
                            onOpenAccessibility = settingsViewModel::openAccessibilitySettings,
                            onCartography = settingsViewModel::dumpGlookoCartography,
                        ),
                        onRefreshGlooko = settingsViewModel::refreshGlooko,
                        voiceActions = VoiceActions(
                            onEngineChange = settingsViewModel::onVoiceEngineChange,
                            onVoiceChange = settingsViewModel::onVoiceChange,
                            onConsentChange = settingsViewModel::onVoiceConsentChange,
                            onKeyInputChange = settingsViewModel::onVoiceKeyInputChange,
                            onSaveKey = settingsViewModel::saveVoiceKey,
                            onClearKey = settingsViewModel::clearVoiceKey,
                            onTryVoice = settingsViewModel::tryVoice,
                        ),
                        mealRecordActions = MealRecordActions(
                            onCamapsAutoChange = settingsViewModel::onCamapsAutoInsertChange,
                            onSuiviChange = settingsViewModel::onSuiviDiabetologueChange,
                            onGlookoCarnetChange = settingsViewModel::onGlookoCarnetChange,
                            onNightscoutNoteChange = settingsViewModel::onNightscoutNoteChange,
                            onAttachPhotoChange = settingsViewModel::onAttachPhotoChange,
                            onOpenAccessibility = settingsViewModel::openAccessibilitySettings,
                        ),
                        homeVoice = homeVoiceState,
                        homeVoiceActions = HomeVoiceActions(
                            onEnabledChange = homeVoiceViewModel::onEnabledChange,
                            onUrlChange = homeVoiceViewModel::onUrlChange,
                            onTokenInputChange = homeVoiceViewModel::onTokenInputChange,
                            onSaveToken = homeVoiceViewModel::saveToken,
                            onForgetToken = homeVoiceViewModel::forgetToken,
                            onTest = homeVoiceViewModel::test,
                            onOpenNotifications = homeVoiceViewModel::openNotificationSettings,
                            onOpenFullScreen = homeVoiceViewModel::openFullScreenSettings,
                            onRefresh = homeVoiceViewModel::refresh,
                        ),
                        wakeWord = wakeWordState,
                        wakeWordActions = WakeWordActions(
                            onEnabledChange = { enable ->
                                val hasMic = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (enable && !hasMic) {
                                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    wakeWordViewModel.onEnabledChange(enable)
                                }
                            },
                            onOpenAppSettings = wakeWordViewModel::openAppSettings,
                            onOpenNotifications = wakeWordViewModel::openNotificationSettings,
                            onOpenFullScreen = wakeWordViewModel::openFullScreenSettings,
                            onRefresh = wakeWordViewModel::refresh,
                        ),
                        onBack = { route = ROUTE_HOME },
                        modifier = Modifier.padding(innerPadding),
                        aiKey = aiKeyState,
                        aiKeyActions = aiKeyViewModel.actions(),
                        portions = portionsState,
                        portionsActions = PortionsActions(
                            onImport = { importPack.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*")) },
                            onExport = { exportPack.launch("mes-portions-miam-miam.zip") },
                            onClearImported = portionsViewModel::clearImported,
                        ),
                        shopping = shoppingState,
                        shoppingActions = ShoppingActions(
                            onToggleChain = shoppingViewModel::toggleChain,
                            onImportFromDevice = {
                                importReceiptDevice.launch(arrayOf("application/pdf", "image/*", "text/plain", "*/*"))
                            },
                            onImportFromGoogleDrive = {
                                val mimeTypes = arrayOf("application/pdf", "image/*", "text/plain")
                                val driveIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = "*/*"
                                    putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                                    setPackage("com.google.android.apps.docs")
                                }
                                try {
                                    importReceiptDrive.launch(driveIntent)
                                } catch (_: ActivityNotFoundException) {
                                    importReceiptDevice.launch(mimeTypes)
                                }
                            },
                            onClearPantry = shoppingViewModel::clearPantry,
                        ),
                    )
                }

                else -> HomeScreen(
                    modifier = Modifier.padding(innerPadding),
                    onDescribeMeal = { route = ROUTE_ASSISTANT },
                    onAnnounceMeal = { route = ROUTE_MANUAL },
                    onHistory = { route = ROUTE_HISTORY },
                    onSettings = { route = ROUTE_SETTINGS },
                )
            }
        }
    }
}
