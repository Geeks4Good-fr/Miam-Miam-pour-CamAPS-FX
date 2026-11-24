package com.najah.mealannouncer.feature.assistant

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.najah.mealannouncer.core.model.CarbResult
import com.najah.mealannouncer.core.model.Carbs
import com.najah.mealannouncer.core.model.MealUnit
import com.najah.mealannouncer.core.model.AutomationOutcome
import com.najah.mealannouncer.core.model.AutomationSource
import com.najah.mealannouncer.core.model.FavoriteMeal
import com.najah.mealannouncer.core.model.FavoriteMealLine
import com.najah.mealannouncer.core.database.repository.FavoriteMealRepository
import com.najah.mealannouncer.core.database.repository.JournalRepository
import com.najah.mealannouncer.core.datastore.MealRecordSettings
import com.najah.mealannouncer.core.datastore.AiSecretStore
import com.najah.mealannouncer.core.datastore.ShoppingSettings
import com.najah.mealannouncer.core.model.FoodSource
import com.najah.mealannouncer.data.homeassistant.ExpectedAnswer
import com.najah.mealannouncer.data.homeassistant.HomeDictation
import com.najah.mealannouncer.data.homeassistant.HomeLink
import com.najah.mealannouncer.data.homeassistant.HomeVoiceLink
import com.najah.mealannouncer.core.database.repository.GeminiMealLogRepository
import com.najah.mealannouncer.core.model.GeminiMealLog
import com.najah.mealannouncer.core.model.GeminiTurn
import com.najah.mealannouncer.data.mistral.AI_DIRECT_MODEL
import com.najah.mealannouncer.data.mistral.ChatRole
import com.najah.mealannouncer.data.nightscout.NightscoutRepository
import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.feature.assistant.portions.PortionBank
import com.najah.mealannouncer.feature.assistant.portions.PortionLookup
import com.najah.mealannouncer.feature.assistant.portions.PortionOption
import com.najah.mealannouncer.data.nightscout.NightscoutSendOutcome
import com.najah.mealannouncer.data.mistral.DetectedFood
import com.najah.mealannouncer.data.mistral.DirectMessage
import com.najah.mealannouncer.data.mistral.DirectSummary
import com.najah.mealannouncer.data.mistral.GeminiDirectChat
import com.najah.mealannouncer.data.mistral.HttpGeminiDirectChat
import com.najah.mealannouncer.data.mistral.jpegDataUri
import com.najah.mealannouncer.data.mistral.MistralException
import com.najah.mealannouncer.data.mistral.MistralTranscriber
import com.najah.mealannouncer.data.mistral.PackagePhotoReader
import com.najah.mealannouncer.data.mistral.PackageReading
import com.najah.mealannouncer.data.mistral.PlateReader
import com.najah.mealannouncer.data.mistral.WebCarbEstimate
import com.najah.mealannouncer.data.mistral.PortionInterpreter
import com.najah.mealannouncer.data.mistral.TranscriptionRequest
import com.najah.mealannouncer.data.openfoodfacts.OffDatabase
import com.najah.mealannouncer.feature.voice.VoiceAnnouncer
import com.najah.mealannouncer.integration.camaps.CamapsMealGateway
import com.najah.mealannouncer.integration.camaps.CamapsMealOutcome
import com.najah.mealannouncer.integration.camaps.CamapsMealRequest
import com.najah.mealannouncer.integration.camaps.CamapsMealType
import com.najah.mealannouncer.integration.camaps.CamapsScreen
import com.najah.mealannouncer.integration.camaps.CamapsVoice
import com.najah.mealannouncer.integration.camaps.CamapsWriteResult
import com.najah.mealannouncer.integration.glookoexport.GlookoCarnet
import com.najah.mealannouncer.integration.glookoexport.GlookoCarnetMoment
import com.najah.mealannouncer.integration.glookoexport.GlookoCarnetResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** Les étapes visibles de la dictée. */
enum class AssistantPhase {
    /** Rien n'est commencé. */
    IDLE,

    /** Le micro est ouvert. */
    RECORDING,

    /** L'enregistrement part chez Mistral. */
    TRANSCRIBING,

    /** La phrase est lue et les aliments cherchés. */
    ANALYSING,

    /** Les lignes sont prêtes : à l'utilisateur de préciser les portions et de valider. */
    READY,

    /** L'assistant pose une question à voix haute. */
    SPEAKING,

    /** Le micro écoute la réponse à la question posée. */
    LISTENING,
}

/**
 * État de l'écran du repas dicté.
 *
 * Le total est recalculé à la lecture : aucune valeur dérivée stockée ne peut se
 * désynchroniser des lignes qu'elle prétend résumer.
 */
data class AssistantUiState(
    val phase: AssistantPhase = AssistantPhase.IDLE,
    val transcript: String = "",
    val lines: List<AssistantLine> = emptyList(),
    val rejected: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val progress: PipelineStep? = null,
    val error: String? = null,
    /** Ce que la dernière photo d'emballage a montré, dit à voix haute et affiché. */
    val photoSeen: String? = null,
    val missingKey: Boolean = false,
    val camaps: CamapsWriteResult? = null,
    /** Nombre de produits de la base Open Food Facts du téléphone, `null` si absente. */
    val localBaseCount: Int? = null,
    /** Faux tant que la présence de la base n'a pas été vérifiée : rien ne s'affiche. */
    val localBaseChecked: Boolean = false,
    /** Le dialogue de portion en cours, `null` quand il n'y en a pas. */
    val dialogue: PortionDialogue? = null,
    /** La proposition issue d'une photo d'assiette, `null` quand il n'y en a pas. */
    val photo: PhotoProposal? = null,
    /** Le dialogue vocal de confirmation CamAPS en cours, `null` quand il n'y en a pas. */
    val camapsConfirm: CamapsConfirmDialogue? = null,
    /** Message après l'écriture des cibles de suivi (Carnet Glooko), `null` quand il n'y en a pas. */
    val recordNotice: String? = null,
    /** La conversation du repas en cours, `null` quand il n'y en a pas. */
    val conversation: ConversationUi? = null,
    /** Les repas favoris : le même petit-déjeuner, le même goûter, annoncés en un geste. */
    val favourites: List<FavoriteMeal> = emptyList(),
    /** La dernière réponse de Gemini en mode direct, `null` hors de ce mode. */
    val direct: DirectAnswerUi? = null,
    /** Photos prises en attente d'envoi (« Prendre une autre photo ? »), `null` s'il n'y en a pas. */
    val photoBatch: PhotoBatchUi? = null,
    /** Vrai quand le mode Gemini direct est actif : l'écran montre le conseil du couvert. */
    val geminiDirect: Boolean = false,
    /** Mode Liberty (24/11/2026) : le repas est noté, jamais annoncé dans CamAPS. */
    val liberty: Boolean = false,
) {

    val busy: Boolean
        get() = phase == AssistantPhase.TRANSCRIBING ||
            phase == AssistantPhase.ANALYSING ||
            photo?.busy == true

    /** Vrai si le dialogue attend une réponse (à l'oral, ou au micro si le téléphone n'entend pas). */
    val dialogueOpen: Boolean get() = dialogue != null

    /** Aliments qui n'ont pas encore de portion : il y a quelque chose à demander. */
    val missingPortions: Int get() = lines.count { it.needsPortion() }

    val resolvedCount: Int get() = lines.count { it.carbResult() is CarbResult.Computed }

    /** Aliments présents mais non chiffrés : jamais comptés comme zéro. */
    val unresolvedCount: Int get() = lines.size - resolvedCount

    val total: Carbs
        get() = Carbs.of(lines.sumOf { it.carbs.grams })

    val camapsGrams: Int get() = total.toCamapsGrams()

    /**
     * Vrai quand au moins un aliment est chiffré : c'est ce qu'on peut écrire dans CamAPS.
     *
     * Le dialogue ferme cette porte : écrire un total partiel pendant qu'une question attend
     * sa réponse reviendrait à annoncer un repas qu'on n'a pas fini de décrire.
     */
    val canWrite: Boolean
        get() = camapsGrams > 0 &&
            phase == AssistantPhase.READY &&
            dialogue == null &&
            photo == null &&
            camapsConfirm == null &&
            conversation == null
}

/** Une fiche Open Food Facts montrée à l'utilisateur en mode Gemini. */
data class OffCardUi(
    val product: FoodProduct,
    /** Le produit tel que Gemini l'a nommé. */
    val askedName: String,
    /** Vrai quand Gemini a dit retenir cette fiche après comparaison des photos. */
    val chosen: Boolean,
)

/** Les portions en images d'un aliment sans marque, en mode Gemini. */
data class DirectPortionChoice(
    /** Le nom donné par Gemini (« riz »). */
    val said: String,
    /** L'aliment de la banque qui lui correspond. */
    val bankName: String,
    val options: List<PortionOption>,
)

/** Les photos prises à l'appareil et pas encore envoyées à Gemini. */
data class PhotoBatchUi(val count: Int)

/** La réponse de Gemini en mode direct, telle que l'écran la montre. */
data class DirectAnswerUi(
    /** La réponse complète, débarrassée du Markdown. */
    val answer: String,
    val totalGrams: Double?,
    val minGrams: Double?,
    val maxGrams: Double?,
    /** Nombre d'échanges déjà faits dans ce repas. */
    val exchanges: Int,
    /** Les fiches Open Food Facts proposées à Gemini, marquées quand il les a retenues. */
    val offCards: List<OffCardUi> = emptyList(),
    /** Les portions en images proposées pour les aliments sans marque. */
    val portionChoices: List<DirectPortionChoice> = emptyList(),
) {
    /** « Total retenu : 60 g (54 à 67 g) », ou `null` si Gemini n'a pas donné de total. */
    val totalLabel: String?
        get() {
            val total = totalGrams ?: return null
            val range = if (minGrams != null && maxGrams != null && maxGrams > minGrams) {
                " (${formatQuantityText(minGrams)} à ${formatQuantityText(maxGrams)} g)"
            } else {
                ""
            }
            return "Total retenu : ${formatQuantityText(total)} g$range"
        }
}

/**
 * La conversation du repas, telle que l'écran la montre : la question de l'assistant, ce que le
 * micro entend, et s'il réfléchit. Une conversation se suit des yeux autant qu'à l'oreille.
 */
data class ConversationUi(
    val question: String = "",
    val heard: String = "",
    val listening: Boolean = false,
    /** Vrai pendant que l'IA comprend la réponse et que l'application cherche les valeurs. */
    val thinking: Boolean = false,
    /** Vrai quand la reconnaissance du téléphone n'a pas répondu : on répond au micro de l'écran. */
    val awaitingMic: Boolean = false,
)

/** L'étape du dialogue vocal de confirmation CamAPS. */
enum class CamapsConfirmStep {
    /** On demande le type de repas (repas, hypo, absorption lente). */
    TYPE,

    /** On répète les glucides et on attend le « oui ». */
    CONFIRM,
}

/**
 * Le dialogue vocal qui précède l'écriture dans CamAPS, en mode insertion automatique.
 *
 * L'assistant demande le type de repas, le répète avec les glucides, et n'écrit — puis n'appuie
 * sur « Continuer » — qu'après un « oui » franc. Tout est à l'écran pour que rien ne se fasse en
 * aveugle : la question, ce qui est entendu, et le moment où il faut répondre au micro.
 */
data class CamapsConfirmDialogue(
    val carbs: Int,
    val question: String,
    val step: CamapsConfirmStep = CamapsConfirmStep.TYPE,
    val chosenType: CamapsMealType? = null,
    val heard: String = "",
    val listening: Boolean = false,
    /** Vrai quand la reconnaissance du téléphone n'a pas répondu : on répond au micro de l'écran. */
    val awaitingMic: Boolean = false,
)

/**
 * Le dialogue de portion en cours : **une question à la fois**.
 *
 * Une seule question est posée à l'oral, pour un seul aliment. Empiler les questions
 * (« et le riz ? et la banane ? ») donnerait une réponse pour deux aliments, et c'est ainsi
 * qu'on chiffre un repas avec les grammes d'un autre.
 */
data class PortionDialogue(
    val lineId: Long,
    val lineName: String,
    val question: String,
    /** Ce que le micro entend, au fur et à mesure : cela montre que le micro est ouvert. */
    val heard: String = "",
    /** Vrai pendant que le micro écoute. */
    val listening: Boolean = false,
    /** Vrai pendant que la réponse est comprise (analyseur, puis modèle si besoin). */
    val thinking: Boolean = false,
    /**
     * Vrai quand la reconnaissance du téléphone n'a pas répondu : la réponse s'écrit alors au
     * micro de l'écran, qui passe par la dictée Voxtral.
     */
    val awaitingMic: Boolean = false,
    /** Nombre de questions déjà posées pour cet aliment : garde-fou contre les boucles. */
    val rounds: Int = 0,
)

/**
 * Ce que la photo d'assiette propose : des lignes **prêtes**, jamais un repas compté.
 *
 * Une photo peut se tromper d'aliment comme de poids, et c'est l'utilisateur qui sait ce qu'il
 * a dans son assiette. Les lignes ne rejoignent donc le repas qu'après un geste explicite, et
 * tant que la proposition est à l'écran, rien ne peut être écrit dans CamAPS.
 */
data class PhotoProposal(
    /** Vrai pendant la lecture de la photo puis la recherche des valeurs. */
    val busy: Boolean = false,
    val lines: List<AssistantLine> = emptyList(),
    /** Aliments que le modèle a proposés sans nom exploitable, ou au-delà du maximum. */
    val rejected: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val error: String? = null,
    /** Où en est la recherche, pendant qu'elle tourne. */
    val step: PipelineStep? = null,
    /** Ce que le modèle a vu sur la photo, dit à voix haute et affiché en tête de la carte. */
    val description: String? = null,
) {

    /** Nombre de propositions déjà chiffrées : le total ne parle que de celles-là. */
    val resolved: Int get() = lines.count { it.carbResult() is CarbResult.Computed }

    /** Ce que les propositions chiffrées donneraient, si l'utilisateur les accepte. */
    val total: Carbs get() = Carbs.of(lines.sumOf { it.carbs.grams })

    /** Vrai quand il y a quelque chose à ajouter : au moins un aliment reconnu. */
    val canAdd: Boolean get() = !busy && lines.isNotEmpty()
}

/**
 * Le repas dicté, de la voix au total.
 *
 * La chaîne est celle du cahier des charges : micro → transcription → lecture des aliments
 * → Open Food Facts, puis Internet pour ce qui manque → **portions demandées à l'écran** →
 * total. Aucun chiffre n'est inventé : un aliment non résolu est affiché comme tel, et il
 * n'est jamais compté comme zéro.
 */
@HiltViewModel
class MealAssistantViewModel @Inject constructor(
    private val recorder: MealAudioRecorder,
    private val transcriber: MistralTranscriber,
    private val localTranscriber: LocalTranscriber,
    private val pipeline: MealCarbPipeline,
    private val keys: AiSecretStore,
    private val camaps: CamapsMealGateway,
    private val mealRecord: MealRecordSettings,
    private val glookoCarnet: GlookoCarnet,
    private val announcer: VoiceAnnouncer,
    private val offDatabase: OffDatabase,
    private val speech: SpeechAnswers,
    private val interpreter: PortionInterpreter,
    private val plateReader: PlateReader,
    private val packageReader: PackagePhotoReader,
    private val photos: PlateImageLoader,
    private val conversation: MealConversationEngine,
    private val homeLink: HomeLink,
    private val phoneLock: PhoneLock,
    private val shopping: ShoppingSettings,
    private val geminiDirect: GeminiDirectChat,
    /** Journal complet des repas Gemini (niveau 1) et note Nightscout (niveau 3). */
    private val geminiLog: GeminiMealLogRepository,
    private val nightscout: NightscoutRepository,
    /** Mode Gemini : fiches Open Food Facts des produits industriels, portions en images des autres. */
    private val productFinder: DirectProductFinder,
    private val portions: PortionLookup,
    private val wakeWordLink: WakeWordLink = WakeWordLink(),
    private val wakeWordArbitrator: WakeWordArbitrator = WakeWordArbitrator(),
    /** Journal d'automatisation (point 3) et repas favoris (point 4). */
    private val journal: JournalRepository,
    private val favorites: FavoriteMealRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    /**
     * Vrai tant qu'un repas attend le déverrouillage pour aller dans CamAPS : l'activité fait alors
     * apparaître le code ou l'empreinte. Essai du 29/10/2026 : l'écran du repas, affiché par-dessus
     * le verrouillage, laissait croire le téléphone déverrouillé.
     */
    private val _unlockWanted = MutableStateFlow(false)

    /** Repas compris mais resté en plan devant CamAPS (description, heure) : repris par un total dicté. */
    private var unsentMeal: Pair<String, Long>? = null
    val unlockWanted: StateFlow<Boolean> = _unlockWanted.asStateFlow()

    /** Faux tant que la base n'a pas été vérifiée : rien ne s'affiche. */
    private var favoriteLineSeq = 1_000_000L

    private var recordingJob: Job? = null

    /** Le dialogue de portion, quand il tourne. */
    private var dialogueJob: Job? = null

    /** La lecture de la photo en cours, quand il y en a une. */
    private var photoJob: Job? = null

    /**
     * La ligne que la prochaine photo doit préciser (poids d'une pièce), ou `null` pour une
     * photo d'assiette ordinaire.
     */
    @Volatile
    private var precisionLineId: Long? = null

    /** Le dialogue vocal de confirmation CamAPS, quand il tourne. */
    private var camapsConfirmJob: Job? = null

    /** La conversation du repas, quand elle tourne. */
    private var conversationJob: Job? = null

    /**
     * La mémoire de la conversation du repas en cours. Elle survit à la fin du dialogue : une
     * correction (« pas ce produit ») reprend la même conversation, avec ses produits refusés.
     */
    private var conversationSession: ConversationSession? = null

    /**
     * La conversation avec Gemini en mode direct : chaque message envoyé (texte, photo) et chaque
     * réponse, dans l'ordre. `null` tant qu'aucun repas n'est commencé dans ce mode ; « Recommencer »
     * la vide. Un message n'y entre qu'avec sa réponse : un échange échoué ne laisse pas de trou.
     */
    private var directMessages: MutableList<DirectMessage>? = null

    /**
     * Le journal complet du repas Gemini en cours (niveau 1 : toute la conversation, tableaux
     * compris, gardée sur le téléphone). Réécrit après chaque échange et chaque envoi.
     */
    private var directLog: GeminiMealLog? = null

    /** Produits déjà cherchés dans Open Food Facts pendant ce repas : chacun ne l'est qu'une fois. */
    private val directLookedUp = mutableSetOf<String>()

    /** Les fiches Open Food Facts proposées à Gemini pendant ce repas, et celles qu'il a retenues. */
    private val directOffCandidates = mutableListOf<OffCandidate>()
    private val directChosenBarcodes = mutableSetOf<String>()

    /** Photos prises à l'appareil et pas encore envoyées : elles partent ensemble, en un seul message. */
    private val pendingDirectPhotos = mutableListOf<Uri>()

    /** Vrai quand le conseil du couvert (échelle des portions) a déjà été dit pour ce repas. */
    private var utensilHintSaid = false

    /**
     * La réponse attendue du micro de l'écran, quand la reconnaissance du téléphone n'a pas
     * répondu : le dialogue l'attend au lieu de se poursuivre sans elle.
     */
    private var pendingAnswer: CompletableDeferred<String?>? = null

    /**
     * L'enceinte qui mène le repas en cours, quand il a été dicté à la maison
     * (`docs/VOIX-MAISON.md`). `null` : le dialogue parle et écoute par le téléphone.
     */
    private var home: HomeVoiceChannel? = null

    /** Vrai quand l'utilisateur a dit ou écrit « ajoute » / « rajoute » seul et que l'on attend l'aliment. */
    private var appendNextInput: Boolean = false

    init {
        // Le mode Gemini direct change les conseils affichés (couvert à côté du plat).
        viewModelScope.launch {
            mealRecord.config.collect { config ->
                _state.update { it.copy(geminiDirect = config.geminiDirect, liberty = config.liberty) }
            }
        }
        // Les favoris suivent la base : un repas enregistré apparaît sans recharger l'écran.
        viewModelScope.launch {
            favorites.observeFavorites().collect { list ->
                _state.update { it.copy(favourites = list) }
            }
        }
        // Savoir si la base locale est là change tout : sans elle, chaque aliment part sur
        // Internet. On le dit à l'écran plutôt que de le laisser deviner.
        viewModelScope.launch {
            val count = offDatabase.productCount()
            _state.update { it.copy(localBaseCount = count, localBaseChecked = true) }
        }
        // Un repas dicté à une enceinte : le service a déjà accusé réception, l'écran mène la suite.
        // Une dictée trop ancienne (personne n'a ouvert l'écran à temps) n'est plus servie.
        viewModelScope.launch {
            homeLink.pending.collect { pending ->
                if (pending != null && homeLink.take(pending)) {
                    val age = System.currentTimeMillis() - pending.receivedAtMs
                    Log.i(HOME_TAG, "Dictée prise par l'écran (${age} ms) : ${pending.dictation.text}")
                    if (age <= HomeLink.PENDING_MAX_AGE_MS) startHomeConversation(homeLink, pending.dictation)
                }
            }
        }
        // Un repas déclenché par le mot-clé « miam-miam » sur le téléphone (docs/MIAM-MIAM.md).
        viewModelScope.launch {
            wakeWordLink.pending.collect { pending ->
                if (pending != null && wakeWordLink.take(pending)) {
                    val age = System.currentTimeMillis() - pending.triggeredAtMs
                    Log.i(WakeWordDetector.TAG, "Réveil « miam-miam » pris par l'écran (${age} ms)")
                    if (age <= WakeWordLink.PENDING_MAX_AGE_MS) onWakeWordTriggered(pending.trigger)
                }
            }
        }
    }

    /**
     * Prend en charge un déclenchement « miam-miam » capté par le téléphone :
     * - phrase dite d'une traite ([WakeWordTrigger.WithAudio]) → transcription directe ;
     * - mot-clé suivi d'une pause ([WakeWordTrigger.WakeOnly]) → annonce « Je t'écoute. » puis
     *   ouverture du micro avec arrêt automatique au silence.
     */
    fun onWakeWordTriggered(trigger: WakeWordTrigger) {
        val running = _state.value.busy || _state.value.phase == AssistantPhase.RECORDING ||
            conversationJob?.isActive == true || dialogueJob?.isActive == true ||
            camapsConfirmJob?.isActive == true
        if (running) return
        when (trigger) {
            is WakeWordTrigger.WithAudio -> {
                if (trigger.wav.isEmpty()) {
                    startWakeWordListening()
                } else {
                    transcribeAndAnalyse(trigger.wav, stripWakeWord = true)
                }
            }
            WakeWordTrigger.WakeOnly -> startWakeWordListening()
        }
    }

    private fun startWakeWordListening() {
        viewModelScope.launch {
            wakeWordArbitrator.acquireMic()
            try {
                _state.update {
                    it.copy(
                        phase = AssistantPhase.SPEAKING,
                        error = null,
                        missingKey = false,
                        camaps = null,
                        recordNotice = null,
                    )
                }
                announcer.announce(WAKE_PHONE_PROMPT)
                delay(BREATH_MS)
                startRecording(stopOnInitialSilence = true)
            } finally {
                wakeWordArbitrator.releaseMic()
            }
        }
    }

    /** Appui sur le micro : ouvre, puis referme et envoie. */
    fun onMicToggled() {
        if (_state.value.phase == AssistantPhase.RECORDING) {
            stopAndTranscribe()
            return
        }
        // Pendant le dialogue, le micro ne sert qu'à répondre à la question posée. Le laisser
        // dicter un repas remplacerait les lignes que le dialogue est en train de chiffrer.
        if (dialogueJob?.isActive == true && _state.value.dialogue?.awaitingMic != true) return
        // Même règle pour la confirmation CamAPS : le micro ne répond que si on l'attend.
        if (camapsConfirmJob?.isActive == true && _state.value.camapsConfirm?.awaitingMic != true) return
        // Et pour la conversation : le micro de l'écran ne sert qu'à répondre à sa question.
        if (conversationJob?.isActive == true && _state.value.conversation?.awaitingMic != true) return
        startRecording()
    }

    /**
     * Autorisation d'enregistrement refusée.
     *
     * On le dit une fois, à l'écran : relancer la demande en boucle ne convainc personne et
     * Android finit par l'ignorer.
     */
    fun onMicDenied() {
        if (_state.value.busy) return
        _state.update {
            it.copy(
                error = "Micro refusé : autorisez l'enregistrement dans les réglages Android " +
                    "pour dicter un repas.",
            )
        }
    }

    private fun startRecording(stopOnInitialSilence: Boolean = false) {
        if (_state.value.busy) return
        val started = recorder.start(
            stopOnInitialSilence = stopOnInitialSilence,
            onSilenceReached = {
                viewModelScope.launch {
                    if (_state.value.phase == AssistantPhase.RECORDING) stopAndTranscribe()
                }
            },
        )
        if (!started) {
            _state.update {
                it.copy(
                    phase = AssistantPhase.IDLE,
                    error = "Micro indisponible : vérifiez l'autorisation d'enregistrement.",
                )
            }
            return
        }
        _state.update {
            it.copy(
                phase = AssistantPhase.RECORDING,
                error = null,
                missingKey = false,
                camaps = null,
                recordNotice = null,
            )
        }
        // Une dictée de repas dure quelques secondes ; au-delà, on s'arrête tout seul
        // plutôt que de laisser le micro ouvert et la facture grossir.
        recordingJob = viewModelScope.launch {
            delay(MAX_RECORDING_MS)
            if (_state.value.phase == AssistantPhase.RECORDING) stopAndTranscribe()
        }
    }

    private fun stopAndTranscribe() {
        recordingJob?.cancel()
        recordingJob = null
        val audio = recorder.stop()
        if (audio.isEmpty()) {
            _state.update {
                it.copy(phase = AssistantPhase.IDLE, error = "Rien n'a été enregistré.")
            }
            return
        }
        transcribeAndAnalyse(audio)
    }

    private fun transcribeAndAnalyse(audio: ByteArray, stripWakeWord: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(phase = AssistantPhase.TRANSCRIBING, error = null) }
            val key = withContext(Dispatchers.IO) { keys.read() }
            if (key.isNullOrBlank()) {
                _state.update {
                    it.copy(
                        phase = AssistantPhase.IDLE,
                        missingKey = true,
                        error = NO_AI_KEY,
                    )
                }
                return@launch
            }
            val rawText = try {
                withContext(Dispatchers.IO) {
                    // D'abord sur le téléphone (Kroko, docs/VOIX-LOCALE.md) ; Mistral si le modèle
                    // manque ou n'a rien reconnu.
                    val started = System.currentTimeMillis()
                    val local = localTranscriber.transcribe(audio)?.takeIf { it.isNotBlank() }
                    if (local != null) {
                        Log.i(HOME_TAG, "Dictée transcrite sur le téléphone (${System.currentTimeMillis() - started} ms) : $local")
                        local
                    } else {
                        transcriber.transcribe(key, TranscriptionRequest(audio))
                    }
                }
            } catch (error: MistralException) {
                _state.update { it.copy(phase = AssistantPhase.IDLE, error = error.message) }
                return@launch
            }

            // Si l'enregistrement a commencé par « miam-miam », on retire le mot-clé en tête.
            val stripped = WakeWordText.stripWakePrefix(rawText)
            if (stripWakeWord && stripped.isBlank()) {
                // La personne n'avait dit que « miam-miam » : on passe en écoute en deux temps.
                startWakeWordListening()
                return@launch
            }
            val text = stripped.ifBlank { rawText.trim() }

            // Le dialogue de portion attend peut-être cette réponse : dans ce cas la dictée
            // est une réponse à une question, pas un nouveau repas à analyser.
            val attending = pendingAnswer
            if (attending != null) {
                _state.update { it.copy(transcript = text, phase = AssistantPhase.READY) }
                attending.complete(text)
                return@launch
            }

            val appendMode = appendNextInput
            appendNextInput = false
            val command = MealCommand.parse(text) ?: if (appendMode) MealCommand.AddFood(text) else null
            if (command != null) {
                executeMealCommand(command, key)
                return@launch
            }

            DirectCarbs.parse(text)?.let { direct ->
                startDirectCarbs(direct)
                return@launch
            }

            _state.update { it.copy(transcript = text, phase = AssistantPhase.ANALYSING) }
            startConversation(key, text)
        }
    }

    /** Corrige la phrase avant de la relire (la dictée n'est pas toujours parfaite). */
    fun onTranscriptChange(text: String) {
        _state.update { it.copy(transcript = text) }
    }

    /** Relance la lecture sur la phrase affichée, sans repasser par le micro. */
    fun analyseTranscript() {
        val rawPhrase = _state.value.transcript.trim()
        if (rawPhrase.isEmpty() || _state.value.busy) return
        val phrase = WakeWordText.stripWakePrefix(rawPhrase).ifBlank { rawPhrase }
        val appendMode = appendNextInput
        appendNextInput = false

        val command = MealCommand.parse(phrase)
        if (command is MealCommand.CancelAll || command is MealCommand.RemoveLastFood) {
            viewModelScope.launch { executeMealCommand(command, apiKey = null) }
            return
        }

        val attending = pendingAnswer
        if (attending != null) {
            attending.complete(phrase)
            return
        }

        val effectiveCommand = command ?: if (appendMode) MealCommand.AddFood(phrase) else null
        // Relire la phrase pendant un dialogue remplacerait les lignes en cours de chiffrage,
        // sauf s'il s'agit d'une commande « ajoute / rajoute ».
        if (effectiveCommand == null &&
            (dialogueJob?.isActive == true || conversationJob?.isActive == true || camapsConfirmJob?.isActive == true)
        ) {
            return
        }

        DirectCarbs.parse(phrase)?.let { direct ->
            startDirectCarbs(direct)
            return
        }

        viewModelScope.launch {
            if (effectiveCommand is MealCommand.AddFood && effectiveCommand.foodText.isBlank()) {
                executeMealCommand(effectiveCommand, apiKey = null)
                return@launch
            }
            val key = withContext(Dispatchers.IO) { keys.read() }
            if (key.isNullOrBlank()) {
                _state.update {
                    it.copy(
                        missingKey = true,
                        error = NO_AI_KEY,
                    )
                }
                return@launch
            }
            if (effectiveCommand != null) {
                executeMealCommand(effectiveCommand, key)
                return@launch
            }
            _state.update { it.copy(phase = AssistantPhase.ANALYSING, error = null) }
            startConversation(key, phrase)
        }
    }

    /**
     * Une phrase de repas dite à une enceinte : la conversation se mène par elle, avec les mêmes
     * règles qu'au téléphone (`docs/VOIX-MAISON.md` § 3). Un repas déjà en cours n'est jamais
     * remplacé (V7) ; sans clé OpenRouter, l'enceinte le dit et rien ne démarre.
     */
    fun startHomeConversation(link: HomeVoiceLink, dictation: HomeDictation) {
        viewModelScope.launch {
            val channel = HomeVoiceChannel(link, dictation.satellite)
            val initialPhrase = if (dictation.isWakeOnly) {
                ""
            } else {
                WakeWordText.stripWakePrefix(dictation.text).ifBlank { dictation.text.trim() }
            }
            val initialCommand = if (initialPhrase.isNotEmpty()) MealCommand.parse(initialPhrase) else null
            if (initialCommand is MealCommand.CancelAll || initialCommand is MealCommand.RemoveLastFood) {
                home = channel
                executeMealCommand(initialCommand, apiKey = null)
                return@launch
            }
            val running = _state.value.busy || _state.value.phase == AssistantPhase.RECORDING ||
                conversationJob?.isActive == true || dialogueJob?.isActive == true ||
                camapsConfirmJob?.isActive == true
            if (running) {
                val said = channel.say(HomeVoiceChannel.BUSY)
                Log.i(HOME_TAG, "Repas déjà en cours : « occupé » dit=$said ${channel.lastFailure?.message.orEmpty()}")
                return@launch
            }
            val key = withContext(Dispatchers.IO) { keys.read() }
            if (dictation.isWakeOnly) {
                if (key.isNullOrBlank()) {
                    channel.say(HomeVoiceChannel.NO_KEY)
                    _state.update {
                        it.copy(
                            missingKey = true,
                            error = NO_AI_KEY,
                        )
                    }
                    return@launch
                }
                home = channel
                // Attend que l'enceinte ait fini son accusé (« Oui ? ») avant d'ouvrir le micro.
                runCatching { link.awaitQuiet(dictation.satellite) }
                val heardMeal = askInitialHomeMeal()
                if (heardMeal.isNullOrBlank()) {
                    say(CONVERSATION_SILENT)
                    home = null
                    _state.update { it.copy(conversation = null, phase = AssistantPhase.READY) }
                    return@launch
                }
                val cleaned = WakeWordText.stripWakePrefix(heardMeal).ifBlank { heardMeal.trim() }
                MealCommand.parse(cleaned)?.let { cmd ->
                    _state.update { it.copy(conversation = null) }
                    executeMealCommand(cmd, key)
                    return@launch
                }
                DirectCarbs.parse(cleaned)?.let { direct ->
                    Log.i(HOME_TAG, "Total dicté après miam-miam : $direct")
                    _state.update { it.copy(conversation = null) }
                    startDirectCarbs(direct)
                    return@launch
                }
                _state.update {
                    it.copy(
                        transcript = cleaned,
                        phase = AssistantPhase.ANALYSING,
                        error = null,
                        missingKey = false,
                        camaps = null,
                        recordNotice = null,
                    )
                }
                startConversation(key, cleaned)
                return@launch
            }
            val phrase = initialPhrase
            if (initialCommand is MealCommand.AddFood) {
                if (key.isNullOrBlank()) {
                    channel.say(HomeVoiceChannel.NO_KEY)
                    _state.update {
                        it.copy(
                            missingKey = true,
                            error = NO_AI_KEY,
                        )
                    }
                    return@launch
                }
                home = channel
                executeMealCommand(initialCommand, key)
                return@launch
            }
            DirectCarbs.parse(phrase)?.let { direct ->
                Log.i(HOME_TAG, "Total dicté : $direct")
                home = channel
                startDirectCarbs(direct)
                return@launch
            }
            if (key.isNullOrBlank()) {
                channel.say(HomeVoiceChannel.NO_KEY)
                _state.update {
                    it.copy(
                        missingKey = true,
                        error = NO_AI_KEY,
                    )
                }
                return@launch
            }
            home = channel
            _state.update {
                it.copy(
                    transcript = phrase,
                    phase = AssistantPhase.ANALYSING,
                    error = null,
                    missingKey = false,
                    camaps = null,
                    recordNotice = null,
                )
            }
            startConversation(key, phrase)
        }
    }

    /**
     * Après « Okay Nabu, miam-miam » ou « ajoute » seul : l'enceinte demande le repas et écoute la
     * réponse (avec un second essai en cas de silence).
     */
    private suspend fun askInitialHomeMeal(initialPrompt: String = WAKE_HOME_PROMPT): String? {
        for (attempt in 0 until 2) {
            val prompt = if (attempt == 0) initialPrompt else "Je n'ai rien entendu. $initialPrompt"
            _state.update {
                it.copy(
                    phase = AssistantPhase.LISTENING,
                    conversation = ConversationUi(question = prompt, listening = true),
                )
            }
            val atHome = askAtHome(prompt)
            _state.update { it.copy(conversation = it.conversation?.copy(listening = false)) }
            when (atHome) {
                is HomeAsk.Done -> if (!atHome.heard.isNullOrBlank()) return atHome.heard
                HomeAsk.Fallback -> return conversationListen(initialPrompt)
            }
        }
        return null
    }

    /** Exécute une commande vocale ou textuelle (« annuler tout le repas », « annuler le dernier aliment », « ajoute / rajoute »). */
    private suspend fun executeMealCommand(command: MealCommand, apiKey: String?) {
        // Mode Gemini direct, repas commencé : « ajoute… » et « retire le dernier » se disent à
        // Gemini, qui tient lui-même la liste du repas. « Annuler tout » reste local.
        if (command !is MealCommand.CancelAll && mealRecord.config.value.geminiDirect && directMessages != null) {
            val text = when (command) {
                MealCommand.RemoveLastFood -> "Retire le dernier aliment, je ne l'ai pas mangé."
                is MealCommand.AddFood -> command.foodText.takeIf { it.isNotBlank() }?.let { "J'ajoute aussi : $it" }
                MealCommand.CancelAll -> null
            }
            val key = apiKey ?: withContext(Dispatchers.IO) { keys.read() }
            if (text != null && !key.isNullOrBlank()) {
                startDirect(key, text)
                return
            }
        }
        when (command) {
            MealCommand.CancelAll -> cancelAllMeal()
            MealCommand.RemoveLastFood -> removeLastFood()
            is MealCommand.AddFood -> addFoodToMeal(command.foodText, apiKey)
        }
    }

    /** Annule et réinitialise tout le repas en cours, puis l'annonce. */
    private suspend fun cancelAllMeal() {
        val currentHome = home
        val count = _state.value.localBaseCount
        val checked = _state.value.localBaseChecked
        unsentMeal = null
        appendNextInput = false
        reset()
        _state.update { it.copy(localBaseCount = count, localBaseChecked = checked) }
        if (currentHome != null) {
            if (!currentHome.say(MEAL_CANCELLED)) {
                announcer.announce(MEAL_CANCELLED)
            }
        } else {
            announcer.announce(MEAL_CANCELLED)
        }
    }

    /** Retire la dernière ligne du repas (état et session) et retourne la ligne retirée, ou `null` si vide. */
    private fun dropLastLine(): AssistantLine? {
        val currentLines = _state.value.lines
        if (currentLines.isEmpty()) return null
        val removed = currentLines.last()
        val remaining = currentLines.dropLast(1)
        conversationSession?.let { session ->
            session.lastLines = session.lastLines.filterNot { it.id == removed.id }
            session.foods = session.foods.filterNot { it.id.toLong() == removed.id }
        }
        _state.update {
            it.copy(
                phase = AssistantPhase.READY,
                transcript = "",
                lines = remaining,
                camaps = null,
                error = null,
                recordNotice = null,
            )
        }
        return removed
    }

    /** Retire le dernier aliment du repas et annonce le nouveau total. */
    private suspend fun removeLastFood() {
        dialogueJob?.cancel()
        dialogueJob = null
        camapsConfirmJob?.cancel()
        camapsConfirmJob = null
        conversationJob?.cancel()
        conversationJob = null
        speech.stop()
        pendingAnswer?.complete(null)
        pendingAnswer = null
        val removed = dropLastLine()
        _state.update {
            it.copy(
                phase = AssistantPhase.READY,
                dialogue = null,
                camapsConfirm = null,
                conversation = null,
                progress = null,
            )
        }
        val message = if (removed == null) {
            EMPTY_REMOVE_MESSAGE
        } else if (_state.value.lines.isEmpty()) {
            "J'ai retiré ${removed.name}. Le repas est vide."
        } else {
            "J'ai retiré ${removed.name}. Total : ${_state.value.total.format()} grammes de glucides."
        }
        say(message)
        home = null
    }

    /** Ajoute un ou plusieurs aliments au repas existant (ou ouvre l'écoute si [foodText] est vide). */
    private suspend fun addFoodToMeal(foodText: String, apiKey: String?) {
        if (foodText.isBlank()) {
            if (home != null) {
                val heard = askInitialHomeMeal(ADD_FOOD_PROMPT)
                if (heard.isNullOrBlank()) {
                    say(CONVERSATION_SILENT)
                    home = null
                    _state.update { it.copy(conversation = null, phase = AssistantPhase.READY) }
                    return
                }
                val cleaned = WakeWordText.stripWakePrefix(heard).ifBlank { heard.trim() }
                val nestedCommand = MealCommand.parse(cleaned)
                if (nestedCommand != null && nestedCommand !is MealCommand.AddFood) {
                    _state.update { it.copy(conversation = null) }
                    executeMealCommand(nestedCommand, apiKey)
                    return
                }
                val toAdd = nestedCommand?.foodText?.ifBlank { cleaned } ?: cleaned
                _state.update { it.copy(conversation = null) }
                addFoodToMeal(toAdd, apiKey)
                return
            }
            startAddFoodListening()
            return
        }
        val key = apiKey?.takeIf { it.isNotBlank() } ?: withContext(Dispatchers.IO) { keys.read() }
        if (key.isNullOrBlank()) {
            _state.update {
                it.copy(
                    phase = AssistantPhase.READY,
                    missingKey = true,
                    error = NO_AI_KEY,
                )
            }
            return
        }
        val existingLines = _state.value.lines
        dialogueJob?.cancel()
        dialogueJob = null
        camapsConfirmJob?.cancel()
        camapsConfirmJob = null
        conversationJob?.cancel()
        conversationJob = null
        _state.update {
            it.copy(
                transcript = foodText,
                phase = AssistantPhase.ANALYSING,
                error = null,
                missingKey = false,
                camaps = null,
                recordNotice = null,
                dialogue = null,
                camapsConfirm = null,
            )
        }
        val session = conversation.newSession(key)
        conversationSession = session
        conversationJob = viewModelScope.launch {
            runConversation(session, foodText, resumed = false, baseLines = existingLines)
        }
    }

    private fun startAddFoodListening() {
        dialogueJob?.cancel()
        dialogueJob = null
        camapsConfirmJob?.cancel()
        camapsConfirmJob = null
        conversationJob?.cancel()
        conversationJob = null
        speech.stop()
        pendingAnswer?.complete(null)
        pendingAnswer = null
        appendNextInput = true
        viewModelScope.launch {
            wakeWordArbitrator.acquireMic()
            try {
                _state.update {
                    it.copy(
                        phase = AssistantPhase.SPEAKING,
                        transcript = "",
                        error = null,
                        missingKey = false,
                        camaps = null,
                        recordNotice = null,
                        dialogue = null,
                        camapsConfirm = null,
                        conversation = null,
                    )
                }
                announcer.announce(ADD_FOOD_PROMPT)
                delay(BREATH_MS)
                startRecording(stopOnInitialSilence = true)
            } finally {
                wakeWordArbitrator.releaseMic()
            }
        }
    }

    /**
     * Un total dicté (« annonce 25 grammes ») : ni recherche ni calcul, on passe droit au type puis
     * à CamAPS. La description du Carnet Glooko est celle du repas resté en plan (obstacle de
     * l'automate) s'il date de moins de [UNSENT_MEAL_MAX_AGE_MS], sinon le total lui-même.
     */
    private fun startDirectCarbs(direct: DirectCarbs) {
        val unsent = unsentMeal?.takeIf { System.currentTimeMillis() - it.second <= UNSENT_MEAL_MAX_AGE_MS }?.first
        val description = unsent ?: "Glucides dictés : ${direct.grams} g"
        _state.update {
            it.copy(transcript = "${direct.grams} grammes", error = null, camaps = null, recordNotice = null)
        }
        camapsConfirmJob = viewModelScope.launch {
            runCamapsConfirm(direct.grams, description, presetType = direct.type, forceGlooko = direct.glooko)
        }
    }

    /** Dit [text] par l'enceinte qui mène le repas, ou par le téléphone ; bascule si elle se tait. */
    private suspend fun say(text: String) {
        val channel = home
        if (channel != null) {
            Log.i(HOME_TAG, "Dit : $text")
            if (channel.say(text)) return
            homeLost(channel)
        }
        announcer.announce(text)
    }

    /** Ce que l'enceinte a rapporté : une réponse (`null` = silence), ou « repasser au téléphone ». */
    private sealed interface HomeAsk {
        data class Done(val heard: String?) : HomeAsk
        data object Fallback : HomeAsk
    }

    /** Pose [question] par l'enceinte, si elle mène le repas. */
    private suspend fun askAtHome(question: String, answers: List<ExpectedAnswer> = emptyList()): HomeAsk {
        val channel = home ?: return HomeAsk.Fallback
        Log.i(HOME_TAG, "Question : $question")
        val heard = channel.ask(question, answers)
        Log.i(HOME_TAG, "Entendu : $heard")
        return when (heard) {
            is HomeVoiceChannel.Heard.Text -> HomeAsk.Done(heard.text)
            HomeVoiceChannel.Heard.Silence -> HomeAsk.Done(null)
            HomeVoiceChannel.Heard.LinkLost -> {
                homeLost(channel)
                HomeAsk.Fallback
            }
        }
    }

    /** L'enceinte ne répond plus : on le dit sur le téléphone, et le repas continue ici (§ 5). */
    private suspend fun homeLost(channel: HomeVoiceChannel) {
        Log.w(HOME_TAG, "Bascule sur le téléphone : ${channel.lastFailure?.message.orEmpty()}")
        home = null
        announcer.announce(HomeVoiceChannel.LINK_LOST)
    }

    /**
     * Ouvre la conversation du repas sur la phrase dictée : l'IA comprend, fait préciser ce qui
     * manque à voix haute, et l'application chiffre après chaque réponse.
     */
    private fun startConversation(key: String, phrase: String) {
        if (mealRecord.config.value.geminiDirect) {
            startDirect(key, phrase)
            return
        }
        conversationJob?.cancel()
        val session = conversation.newSession(key)
        conversationSession = session
        conversationJob = viewModelScope.launch { runConversation(session, phrase, resumed = false) }
    }

    /**
     * Mode Gemini direct : [text] (et les photos [images], s'il y en a) part dans la conversation
     * du repas, telle quelle. Une conversation déjà en cours est coupée : son historique, lui, reste.
     */
    private fun startDirect(key: String, text: String, images: List<Uri> = emptyList()) {
        conversationJob?.cancel()
        conversationJob = viewModelScope.launch { runDirect(key, text, images) }
    }

    /**
     * La boucle du mode Gemini direct (`docs/DECISIONS.md` § 50).
     *
     * Gemini répond comme sur son site ; un second modèle, court, lit sa réponse pour en tirer le
     * total, la phrase à dire, la question éventuelle, les produits industriels et les aliments sans
     * marque — sans rien recalculer. Pour un produit industriel, l'appli cherche sa fiche Open Food
     * Facts et la renvoie à Gemini avec sa photo : Gemini compare visuellement, choisit la fiche,
     * recalcule et demande la quantité si elle manque (demande du 23/11/2026). Le total devient une
     * seule ligne du repas, que les boutons habituels (CamAPS, favoris) traitent comme les autres.
     * La boucle s'arrête quand Gemini ne pose plus de question ; elle ne part seule vers CamAPS que si
     * le total couvre tout le repas et que l'insertion automatique est réglée (le « oui » vocal de la
     * confirmation reste exigé).
     */
    private suspend fun runDirect(key: String, firstText: String, firstImages: List<Uri>) {
        var text = firstText
        var images = firstImages
        var chainCamaps = false
        var endedByCommand = false
        try {
            for (turn in 0 until MAX_CONVERSATION_TURNS) {
                _state.update {
                    it.copy(
                        phase = AssistantPhase.ANALYSING,
                        error = null,
                        camaps = null,
                        recordNotice = null,
                        conversation = (it.conversation ?: ConversationUi())
                            .copy(thinking = true, listening = false, awaitingMic = false),
                    )
                }
                val photoData = readDirectPhotos(images) ?: return
                images = emptyList()
                var (answer, summary) = directExchange(key, DirectMessage(ChatRole.USER, text, photoData)) ?: return
                saveDirectExchange(text, withPhoto = photoData.isNotEmpty(), answer = answer, summary = summary)

                // Produits industriels : leur fiche Open Food Facts, comparée par Gemini à la photo.
                val fresh = summary.products.filter { directLookedUp.size < MAX_PRODUCT_LOOKUPS && directLookedUp.add(productKey(it.name, it.brand)) }
                if (fresh.isNotEmpty()) {
                    val candidates = productFinder.find(fresh)
                    Log.i(DIRECT_TAG, "Open Food Facts : ${candidates.size} fiche(s) pour ${fresh.map { it.name }}")
                    if (candidates.isNotEmpty()) {
                        directOffCandidates += candidates
                        _state.update { it.copy(direct = it.direct?.copy(offCards = offCards(emptyList()))) }
                        val offText = DirectProductFinder.message(candidates)
                        val offImages = candidates.mapNotNull { it.imageDataUri }
                        directExchange(key, DirectMessage(ChatRole.USER, offText, offImages))?.let { (offAnswer, offSummary) ->
                            answer = offAnswer
                            summary = offSummary
                            saveDirectExchange(offText, withPhoto = offImages.isNotEmpty(), answer = answer, summary = summary)
                        }
                    }
                }
                directChosenBarcodes += summary.chosenBarcodes
                // La banque de portions lit ses fichiers : hors du fil de l'écran.
                val choices = withContext(Dispatchers.IO) { portionChoices(summary.plainFoods) }
                applyDirectAnswer(answer, summary, exchanges = (directMessages?.size ?: 0) / 2, choices = choices)
                logMeal("Gemini direct, échange ${(directMessages?.size ?: 0) / 2}")

                say(summary.spoken)
                val question = summary.question
                if (question == null) {
                    val grams = _state.value.camapsGrams
                    chainCamaps = summary.complete && grams > 0 && chainsAfterTotal()
                    return
                }
                val heard = conversationListen(question) ?: run {
                    say(if (_state.value.camapsGrams > 0) DIRECT_SILENT_WITH_TOTAL else CONVERSATION_SILENT)
                    return
                }
                if (MealCommand.parse(heard) == MealCommand.CancelAll) {
                    endedByCommand = true
                    cancelAllMeal()
                    return
                }
                text = heard
            }
            say(CONVERSATION_TOO_LONG)
        } finally {
            speech.stop()
            pendingAnswer = null
            if (!endedByCommand) {
                if (!chainCamaps) home = null
                _state.update {
                    it.copy(
                        conversation = null,
                        progress = null,
                        phase = AssistantPhase.READY,
                        transcript = "",
                    )
                }
                if (chainCamaps) writeToCamaps()
            }
        }
    }

    /**
     * Lit les photos à envoyer (réduites en JPEG). Rend `null` — et le dit — si l'une d'elles est
     * illisible ; les fichiers pris par l'appli sont effacés dans tous les cas.
     */
    private suspend fun readDirectPhotos(uris: List<Uri>): List<String>? {
        val data = mutableListOf<String>()
        for (uri in uris) {
            val one = try {
                photos.jpeg(uri)?.takeIf { it.isNotEmpty() }?.let(::jpegDataUri)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                null
            } finally {
                // Une photo prise par l'appli n'a pas à rester sur le téléphone.
                photos.forget(uri)
            }
            if (one == null) {
                uris.forEach(photos::forget)
                _state.update { it.copy(error = PHOTO_UNREADABLE) }
                return null
            }
            data += one
        }
        return data
    }

    /**
     * Un échange avec Gemini : [message] part avec tout l'historique, la réponse est lue, puis les
     * deux entrent dans l'historique. Rend `null` — et le dit — en cas d'échec.
     */
    private suspend fun directExchange(key: String, message: DirectMessage): Pair<String, DirectSummary>? {
        val history = directMessages ?: mutableListOf<DirectMessage>().also { directMessages = it }
        val waitSpoken = AtomicBoolean(false)
        val waiting = viewModelScope.launch {
            delay(WAIT_PHRASE_MS)
            waitSpoken.set(true)
            say(WAIT_PHRASE)
        }
        val started = System.currentTimeMillis()
        val result = try {
            withContext(Dispatchers.IO) {
                val reply = geminiDirect.reply(key, history + message)
                reply to geminiDirect.summarize(key, reply, message.text)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // MistralException surtout ; toute autre panne se dit aussi, sans faire tomber l'appli.
            Log.w(DIRECT_TAG, "Échange en échec : ${error.message}")
            _state.update { it.copy(progress = null, error = error.message ?: CONVERSATION_FAILED) }
            say(CONVERSATION_FAILED)
            return null
        } finally {
            if (waitSpoken.get() && currentCoroutineContext().isActive) waiting.join() else waiting.cancel()
        }
        history += message
        history += DirectMessage(ChatRole.ASSISTANT, result.first)
        val summary = result.second
        Log.i(
            DIRECT_TAG,
            "Échange ${history.size / 2} (${message.images.size} photo(s)) en ${System.currentTimeMillis() - started} ms : " +
                "total ${summary.totalGrams} g (${summary.minGrams}–${summary.maxGrams}), complet=${summary.complete}, " +
                "question=${summary.question}, produits=${summary.products.map { it.name }}, sans marque=${summary.plainFoods}",
        )
        return result
    }

    /** Les cartes Open Food Facts à l'écran : chaque fiche proposée, marquée si Gemini l'a retenue. */
    private fun offCards(chosen: Collection<String>): List<OffCardUi> {
        val retained = chosen.toSet() + directChosenBarcodes
        return directOffCandidates.distinctBy { it.product.id }.map { candidate ->
            OffCardUi(
                product = candidate.product,
                askedName = candidate.asked.name,
                chosen = candidate.product.barcode != null && candidate.product.barcode in retained,
            )
        }
    }

    /** Les portions en images proposées pour les aliments sans marque de la réponse (banque de portions). */
    private fun portionChoices(plainFoods: List<String>): List<DirectPortionChoice> =
        plainFoods.mapNotNull { name ->
            val match = portions.match(name) ?: return@mapNotNull null
            val options = portions.options(match.toProduct().id).filter { it.imagePath != null }
            if (options.isEmpty()) null else DirectPortionChoice(said = name, bankName = match.food.name, options = options)
        }.distinctBy { it.bankName }

    /**
     * Une portion en images touchée en mode Gemini : elle part à Gemini comme une précision de
     * l'utilisateur, et Gemini refait son calcul.
     */
    fun pickDirectPortion(said: String, option: PortionOption) {
        viewModelScope.launch {
            val key = withContext(Dispatchers.IO) { keys.read() } ?: return@launch
            if (key.isBlank()) return@launch
            val label = option.label?.let { "« $it »" } ?: "de ${formatQuantityText(option.grams)} g"
            startDirect(key, "Pour « $said », ma portion ressemble à la photo $label, soit environ ${formatQuantityText(option.grams)} g.")
        }
    }

    private fun productKey(name: String, brand: String?): String =
        PortionBank.normalize(listOfNotNull(brand, name).joinToString(" "))

    /** Niveau 1 : ajoute l'échange (message exact, réponse complète de Gemini) au journal du repas. */
    private suspend fun saveDirectExchange(text: String, withPhoto: Boolean, answer: String, summary: DirectSummary) {
        val now = Instant.now()
        val previous = directLog
        val log = GeminiMealLog(
            id = previous?.id ?: UUID.randomUUID().toString(),
            startedAt = previous?.startedAt ?: now,
            updatedAt = now,
            totalCarbs = summary.totalGrams,
            minCarbs = summary.minGrams,
            maxCarbs = summary.maxGrams,
            meal = summary.meal ?: previous?.meal.orEmpty(),
            detail = summary.detail ?: previous?.detail.orEmpty(),
            model = AI_DIRECT_MODEL,
            turns = previous?.turns.orEmpty() +
                GeminiTurn(fromUser = true, text = text, withPhoto = withPhoto) +
                GeminiTurn(fromUser = false, text = answer, totalCarbs = summary.totalGrams),
            destinations = previous?.destinations.orEmpty(),
        )
        directLog = log
        geminiLog.save(log)
    }

    /** Note dans le journal du repas Gemini ce qui est parti vers CamAPS, Glooko ou Nightscout. */
    private suspend fun logDirectDestination(line: String) {
        val log = directLog ?: return
        val stamped = "${LocalTime.now().withNano(0)} — $line"
        val updated = log.copy(updatedAt = Instant.now(), destinations = log.destinations + stamped)
        directLog = updated
        geminiLog.save(updated)
    }

    /**
     * La description du repas pour le Carnet Glooko et Nightscout : en mode Gemini, le détail
     * aliment par aliment lu dans sa réponse ; sinon, les lignes du repas.
     */
    private fun mealDescription(lines: List<AssistantLine>): String =
        directLog?.detail?.takeIf { it.isNotBlank() }
            ?: directLog?.meal?.takeIf { it.isNotBlank() }
            ?: MealDescription.of(lines)

    /** Affiche la réponse de Gemini et fait de son total l'unique ligne du repas. */
    private fun applyDirectAnswer(
        answer: String,
        summary: DirectSummary,
        exchanges: Int,
        choices: List<DirectPortionChoice> = emptyList(),
    ) {
        val total = summary.totalGrams?.takeIf { it > 0.0 }
        val low = summary.minGrams
        val high = summary.maxGrams
        val range = if (low != null && high != null && high > low) {
            "${formatQuantityText(low)} à ${formatQuantityText(high)} g"
        } else {
            null
        }
        val line = total?.let {
            AssistantLine(
                id = 1L,
                name = summary.meal ?: DIRECT_LINE_NAME,
                rawText = summary.meal ?: DIRECT_LINE_NAME,
                quantityText = "1",
                unit = MealUnit.PORTION,
                estimate = WebCarbEstimate(
                    name = DIRECT_LINE_NAME,
                    carbsPer100g = 0.0,
                    sourceUrl = null,
                    general = true,
                    carbsPerUnit = it,
                    unitDetail = range,
                ),
                portionNote = "Total de Gemini" + (range?.let { r -> " (fourchette $r)" } ?: ""),
            )
        }
        _state.update {
            it.copy(
                lines = listOfNotNull(line),
                rejected = emptyList(),
                warnings = emptyList(),
                photoSeen = null,
                photo = null,
                direct = DirectAnswerUi(
                    answer = HttpGeminiDirectChat.plainText(answer),
                    totalGrams = total,
                    minGrams = summary.minGrams,
                    maxGrams = summary.maxGrams,
                    exchanges = exchanges,
                    offCards = offCards(summary.chosenBarcodes),
                    portionChoices = choices,
                ),
                conversation = it.conversation?.copy(thinking = false),
            )
        }
    }

    /**
     * Note dans le journal le repas tel que l'écran le montre : chaque ligne (quantité, unité,
     * glucides, source, doute) et le total. Sert à vérifier un essai réel sans demander à
     * l'utilisateur ce qu'il a vu (demande de l'utilisateur, 10/11/2026). Aucun secret, aucune glycémie.
     */
    private fun logMeal(moment: String) {
        val state = _state.value
        val pantryBarcodes = shopping.config.value.pantryProducts.mapNotNullTo(HashSet()) { it.barcode }
        val lines = state.lines.joinToString(" | ") { line ->
            val carbs = (line.carbResult() as? CarbResult.Computed)?.carbs?.format()?.plus(" g") ?: "non chiffré"
            val doubt = when {
                line.carbsGuessed -> ", glucides estimés"
                line.weightGuessed -> ", poids estimé"
                else -> ""
            }
            val source = line.product?.source?.name ?: line.origin?.name ?: "sans source"
            // D'où vient la fiche : photo, facture importée (garde-manger) ou recherche dite.
            val via = conversationSession?.let { conversation.photoOrigin(it, line) }
                ?: line.product?.takeIf { p -> p.barcode != null && pantryBarcodes.contains(p.barcode) }?.let { "facture" }
                ?: line.product?.let { if (it.source == FoodSource.PORTION_BANK) "banque de portions" else "recherche" }
                ?: "estimation"
            val optionsCount = if (line.portionOptions.isNotEmpty()) ", ${line.portionOptions.size} photos" else ""
            "#${line.id} ${line.name} : ${line.quantityText} ${unitLabel(line.unit)} → $carbs " +
                "($source via $via$optionsCount$doubt)" + (line.portionNote?.let { " [$it]" } ?: "")
        }
        Log.i("MealAnnouncerMeal", "$moment — total ${state.total.format()} g (CamAPS ${state.camapsGrams} g) : $lines")
    }

    /** Reprend la conversation du repas avec [utterance] — une correction, en général. */
    private fun resumeConversation(utterance: String) {
        val session = conversationSession ?: return
        conversationJob?.cancel()
        conversationJob = viewModelScope.launch { runConversation(session, utterance, resumed = true) }
    }

    /**
     * Bouton « Pas ce produit » : le produit retenu pour cette ligne est écarté du repas, et la
     * conversation reprend pour en trouver un autre — ou demander lequel c'est.
     */
    fun rejectProduct(lineId: Long) {
        val state = _state.value
        if (state.busy || state.phase != AssistantPhase.READY) return
        if (conversationJob?.isActive == true || camapsConfirmJob?.isActive == true ||
            dialogueJob?.isActive == true
        ) {
            return
        }
        // En mode direct, la ligne est le total de Gemini : on le corrige en lui parlant.
        if (directMessages != null) return
        val line = state.lines.firstOrNull { it.id == lineId } ?: return
        val session = conversationSession
        if (session != null) {
            conversation.reject(session, line)
            resumeConversation("Ce n'est pas le bon produit pour l'aliment #${line.id} : « ${line.name} ».")
            return
        }
        // Des lignes venues d'une photo n'ont pas de conversation : on en ouvre une sur le repas
        // affiché, avec le produit déjà écarté.
        viewModelScope.launch {
            val key = withContext(Dispatchers.IO) { keys.read() }
            if (key.isNullOrBlank()) {
                _state.update { it.copy(missingKey = true, error = NO_AI_KEY) }
                return@launch
            }
            val fresh = conversation.newSession(key)
            conversation.reject(fresh, line)
            conversationSession = fresh
            val phrase = state.transcript.ifBlank { state.lines.joinToString(", ") { it.rawText } }
            conversationJob?.cancel()
            conversationJob = viewModelScope.launch { runConversation(fresh, phrase, resumed = false) }
        }
    }

    /** Coupe la conversation : plus de question, plus de micro. Les lignes déjà chiffrées restent. */
    fun stopConversation() {
        conversationJob?.cancel()
        conversationJob = null
        home = null
        speech.stop()
        pendingAnswer?.complete(null)
        pendingAnswer = null
        _state.update { it.copy(conversation = null, progress = null, phase = AssistantPhase.READY) }
    }

    /**
     * La boucle de la conversation.
     *
     * Elle s'arrête quand l'IA n'a plus de question, quand l'utilisateur le demande, après deux
     * silences, après trois réponses incomprises (on propose alors la photo) ou après
     * [MAX_CONVERSATION_TURNS] échanges : les lignes restent à l'écran, modifiables à la main.
     * Quand tout est chiffré et que l'insertion automatique est réglée, la confirmation CamAPS
     * prend le relais — c'est elle qui annonce le repas, une seule fois.
     */
    private suspend fun runConversation(
        session: ConversationSession,
        phrase: String,
        resumed: Boolean,
        baseLines: List<AssistantLine> = emptyList(),
    ) {
        var currentSession = session
        var currentBaseLines = baseLines
        var utterance = phrase
        var misunderstood = 0
        var chainCamaps = false
        var endedByCommand = false
        try {
            for (turn in 0 until MAX_CONVERSATION_TURNS) {
                _state.update {
                    it.copy(
                        phase = AssistantPhase.ANALYSING,
                        error = null,
                        conversation = (it.conversation ?: ConversationUi())
                            .copy(thinking = true, listening = false, awaitingMic = false),
                    )
                }
                // Un silence de plus de quelques secondes passe pour une panne (§ 4 bis de VOIX-MAISON) :
                // on le meuble, une fois par échange. Commencée, la phrase va jusqu'au bout — la question
                // suivante ne doit pas partir pendant que l'enceinte parle encore.
                val waitSpoken = AtomicBoolean(false)
                val waiting = viewModelScope.launch {
                    delay(WAIT_PHRASE_MS)
                    waitSpoken.set(true)
                    say(WAIT_PHRASE)
                }
                val turnStarted = System.currentTimeMillis()
                val step = try {
                    conversation.turn(currentSession, utterance) { progress ->
                        Log.i(HOME_TAG, "Étape (+${System.currentTimeMillis() - turnStarted} ms) : $progress")
                        _state.update { it.copy(progress = progress) }
                    }.also {
                        Log.i("MealAnnouncerTiming", "Échange ${turn + 1} calculé en ${System.currentTimeMillis() - turnStarted} ms")
                    }
                } catch (error: MistralException) {
                    _state.update { it.copy(progress = null, error = error.message) }
                    say(CONVERSATION_FAILED)
                    return
                } finally {
                    if (waitSpoken.get() && currentCoroutineContext().isActive) waiting.join() else waiting.cancel()
                }
                if (step.cancelled) {
                    say(CONVERSATION_CANCELLED)
                    return
                }
                val idOffset = currentBaseLines.maxOfOrNull { it.id } ?: 0L
                val stepLines = if (idOffset > 0L) {
                    step.lines.map { line -> line.copy(id = line.id + idOffset) }
                } else {
                    step.lines
                }
                _state.update { state ->
                    val mergedLines = if (stepLines.isEmpty() && (turn > 0 || resumed)) {
                        state.lines
                    } else {
                        currentBaseLines + stepLines
                    }
                    state.copy(
                        // Une phrase incomprise ne vide pas le repas déjà compris.
                        lines = mergedLines,
                        rejected = step.rejected,
                        // Les doutes de la relecture s'affichent avec les remarques : ils disent pourquoi
                        // le repas ne part pas seul vers CamAPS.
                        warnings = step.warnings + step.doubts,
                        progress = null,
                        camaps = null,
                        conversation = state.conversation?.copy(thinking = false),
                    )
                }
                logMeal("Échange ${turn + 1}")

                misunderstood = if (step.understood) 0 else misunderstood + 1
                if (misunderstood >= MAX_MISUNDERSTOOD) {
                    say(CONVERSATION_PHOTO)
                    return
                }

                val question = step.question
                if (question == null) {
                    chainCamaps = concludeConversation(step.doubts)
                    return
                }
                val heard = conversationListen(question) ?: run {
                    say(CONVERSATION_SILENT)
                    return
                }
                when (val cmd = MealCommand.parse(heard)) {
                    MealCommand.CancelAll -> {
                        endedByCommand = true
                        cancelAllMeal()
                        return
                    }
                    MealCommand.RemoveLastFood -> {
                        endedByCommand = true
                        removeLastFood()
                        return
                    }
                    is MealCommand.AddFood -> {
                        val toAdd = cmd.foodText.ifBlank {
                            conversationListen(ADD_FOOD_PROMPT) ?: run {
                                say(CONVERSATION_SILENT)
                                return
                            }
                        }
                        currentBaseLines = _state.value.lines
                        currentSession = conversation.newSession(currentSession.apiKey)
                        conversationSession = currentSession
                        utterance = toAdd
                        continue
                    }
                    null -> utterance = heard
                }
            }
            say(CONVERSATION_TOO_LONG)
        } finally {
            speech.stop()
            pendingAnswer = null
            if (!endedByCommand) {
                currentSession.lastLines = _state.value.lines
                if (!chainCamaps) home = null
                _state.update {
                    it.copy(conversation = null, progress = null, phase = AssistantPhase.READY)
                }
                if (chainCamaps) writeToCamaps()
            }
        }
    }

    /**
     * Fin de la conversation : rend vrai si la confirmation CamAPS doit prendre le relais.
     *
     * Sinon, l'assistant dit le repas compris et le total — ou ce qui reste à préciser à l'écran.
     */
    private suspend fun concludeConversation(doubts: List<String> = emptyList()): Boolean {
        val state = _state.value
        if (state.lines.isEmpty()) {
            say(CONVERSATION_EMPTY)
            return false
        }
        // Un écart que la relecture n'a pas levé : jamais d'envoi automatique vers CamAPS. Le détail
        // est dit, et le repas attend à l'écran (`docs/DECISIONS.md` § 43).
        if (doubts.isNotEmpty()) {
            say(CONVERSATION_DOUBT + " " + spokenDetail(state.lines) + ". Vérifie à l'écran avant de valider.")
            return false
        }
        // Poids d'une pièce estimé alors que l'emballage le dit : pas d'envoi automatique, on
        // propose la photo de l'emballage (décision du 10/11/2026, au lieu de 17 s de recherche web).
        if (state.lines.any { it.photoWouldHelp }) {
            say(weightGuessedSpeech(state.lines))
            return false
        }
        val allResolved = state.unresolvedCount == 0
        if (allResolved && state.camapsGrams > 0 && chainsAfterTotal()) {
            return true
        }
        val names = state.lines.joinToString(", ") { it.name }
        say(
            "J'ai compris : $names. " + PortionQuestions.finished(state.total, state.unresolvedCount),
        )
        return false
    }

    /**
     * Pose la question de l'assistant, puis écoute — en la reposant une fois si rien n'est
     * entendu. Rend `null` après deux silences.
     */
    private suspend fun conversationListen(question: String): String? {
        for (attempt in 0 until 2) {
            val said = if (attempt == 0) question else "Je n'ai rien entendu. $question"
            val heard = conversationAskAndListen(said)
            if (!heard.isNullOrBlank()) return heard
        }
        return null
    }

    /** Dit [question] et écoute la réponse, par le téléphone ou, à défaut, par le micro de l'écran. */
    private suspend fun conversationAskAndListen(question: String): String? {
        _state.update {
            it.copy(
                phase = AssistantPhase.SPEAKING,
                conversation = ConversationUi(question = question),
            )
        }
        if (home != null) {
            _state.update {
                it.copy(phase = AssistantPhase.LISTENING, conversation = it.conversation?.copy(listening = true))
            }
            val atHome = askAtHome(question)
            _state.update { it.copy(conversation = it.conversation?.copy(listening = false)) }
            if (atHome is HomeAsk.Done) return atHome.heard
        }
        say(question)
        delay(BREATH_MS)

        if (!speech.available) return awaitWrittenConversation(question)

        _state.update {
            it.copy(
                phase = AssistantPhase.LISTENING,
                conversation = it.conversation?.copy(listening = true),
            )
        }
        val heard = speech.listen { partial ->
            _state.update { state -> state.copy(conversation = state.conversation?.copy(heard = partial)) }
        }
        _state.update { it.copy(conversation = it.conversation?.copy(listening = false)) }
        if (!heard.isNullOrBlank()) return heard
        return awaitWrittenConversation(question)
    }

    /** Attend la réponse passée par le micro de l'écran, quand le téléphone n'a pas entendu. */
    private suspend fun awaitWrittenConversation(question: String): String? {
        _state.update {
            it.copy(
                phase = AssistantPhase.READY,
                conversation = it.conversation?.copy(
                    question = question,
                    listening = false,
                    awaitingMic = true,
                ),
            )
        }
        val answer = CompletableDeferred<String?>()
        pendingAnswer = answer
        return try {
            withTimeoutOrNull(MIC_ANSWER_TIMEOUT_MS) { answer.await() }
        } finally {
            pendingAnswer = null
        }
    }

    /**
     * Un fichier temporaire où l'appareil photo écrira.
     *
     * Le fichier vit dans le cache de l'application : la photo n'apparaît pas dans la galerie et
     * disparaît dès qu'elle a été lue.
     */
    fun newPhotoTarget(): Uri {
        precisionLineId = null
        return photos.newTarget()
    }

    /**
     * Bouton « Photographier l'emballage » d'une ligne au poids estimé : la prochaine photo
     * sert à préciser le poids d'une pièce de cette ligne, pas à proposer un repas.
     */
    fun newPrecisionPhotoTarget(lineId: Long): Uri {
        precisionLineId = lineId
        return photos.newTarget()
    }

    /**
     * Photo prise à l'instant. En mode Gemini, elle attend dans le lot et l'écran demande s'il faut
     * en prendre d'autres (demande du 23/11/2026) ; sinon, on la lit tout de suite.
     */
    fun onPhotoCaptured(uri: Uri) {
        if (precisionLineId == null && mealRecord.config.value.geminiDirect) {
            pendingDirectPhotos += uri
            val count = pendingDirectPhotos.size
            _state.update { it.copy(photoBatch = PhotoBatchUi(count), error = null) }
            viewModelScope.launch { say(if (count == 1) MORE_PHOTOS_FIRST else MORE_PHOTOS_NEXT.format(count)) }
            return
        }
        routePhoto(uri)
    }

    /** Photo choisie dans le téléphone : lue en place, jamais copiée ni modifiée. */
    fun onPhotoPicked(uri: Uri) {
        routePhoto(uri)
    }

    /**
     * Plusieurs photos choisies d'un coup dans le téléphone. En mode Gemini, elles partent ensemble,
     * dans un seul message ; sinon, seule la première est lue (lecture d'assiette habituelle).
     */
    fun onPhotosPicked(uris: List<Uri>) {
        if (uris.isEmpty()) {
            onPhotoCancelled(null)
            return
        }
        if (precisionLineId == null && mealRecord.config.value.geminiDirect) {
            sendDirectPhotos(uris)
            return
        }
        routePhoto(uris.first())
    }

    /** « Envoyer à Gemini » : les photos du lot partent ensemble. */
    fun sendPhotoBatch() {
        val batch = pendingDirectPhotos.toList()
        pendingDirectPhotos.clear()
        _state.update { it.copy(photoBatch = null) }
        if (batch.isNotEmpty()) sendDirectPhotos(batch)
    }

    /** « Annuler » : les photos du lot sont effacées, rien ne part. */
    fun cancelPhotoBatch() {
        pendingDirectPhotos.forEach(photos::forget)
        pendingDirectPhotos.clear()
        _state.update { it.copy(photoBatch = null) }
    }

    /**
     * L'appareil photo va s'ouvrir : au premier cliché d'un repas Gemini, le conseil du couvert est
     * dit (une fourchette à côté du plat sert d'échelle pour estimer les portions).
     */
    fun onCameraOpening() {
        if (!mealRecord.config.value.geminiDirect || utensilHintSaid || pendingDirectPhotos.isNotEmpty()) return
        utensilHintSaid = true
        viewModelScope.launch { say(UTENSIL_HINT) }
    }

    private fun sendDirectPhotos(uris: List<Uri>) {
        viewModelScope.launch {
            val key = withContext(Dispatchers.IO) { keys.read() }
            if (key.isNullOrBlank()) {
                uris.forEach(photos::forget)
                _state.update { it.copy(missingKey = true, error = PHOTO_NEEDS_KEY) }
                return@launch
            }
            val typed = _state.value.transcript.trim()
            val first = directMessages.isNullOrEmpty()
            val text = when {
                first && typed.isNotEmpty() -> typed
                first -> DIRECT_FIRST_PHOTO
                uris.size > 1 -> DIRECT_MORE_PHOTOS
                else -> DIRECT_MORE_PHOTO
            }
            startDirect(key, text, uris)
        }
    }

    private fun routePhoto(uri: Uri) {
        val lineId = precisionLineId
        precisionLineId = null
        // Mode Gemini direct : la photo part telle quelle dans la conversation, comme sur le site.
        if (lineId == null && mealRecord.config.value.geminiDirect) {
            viewModelScope.launch {
                val key = withContext(Dispatchers.IO) { keys.read() }
                if (key.isNullOrBlank()) {
                    photos.forget(uri)
                    _state.update { it.copy(missingKey = true, error = PHOTO_NEEDS_KEY) }
                    return@launch
                }
                val typed = _state.value.transcript.trim()
                val text = when {
                    directMessages.isNullOrEmpty() && typed.isNotEmpty() -> typed
                    directMessages.isNullOrEmpty() -> DIRECT_FIRST_PHOTO
                    else -> DIRECT_MORE_PHOTO
                }
                startDirect(key, text, listOf(uri))
            }
            return
        }
        // Pendant un repas, une photo sert à le préciser (emballage, tableau nutritionnel) ;
        // hors repas, elle propose les aliments d'une assiette.
        val mealInProgress = conversationSession != null && _state.value.lines.isNotEmpty()
        if (lineId != null || mealInProgress) analysePackagePhoto(uri, lineId) else analysePhoto(uri)
    }

    /** L'appareil photo a été abandonné : le fichier temporaire n'a plus lieu d'être. */
    fun onPhotoCancelled(uri: Uri?) {
        uri?.let(photos::forget)
        if (precisionLineId != null) {
            // Photo d'emballage abandonnée : le repas reste tel quel, avec son estimation.
            precisionLineId = null
            return
        }
        photoJob?.cancel()
        photoJob = null
        _state.update { it.copy(photo = null) }
    }

    /**
     * Lit la photo d'un emballage pendant un repas : ce que le modèle a vu est **dit à voix
     * haute** et affiché ; les glucides pour 100 g et le poids d'une pièce lus sont retenus pour
     * l'aliment montré, puis la conversation reprend pour rechiffrer (et relire) le repas.
     *
     * [targetLineId] : la ligne dont on a touché le bouton, sinon le modèle dit laquelle la photo
     * montre. Une photo qui ne dit rien de sûr laisse le repas tel quel, et l'écran le dit.
     */
    private fun analysePackagePhoto(uri: Uri, targetLineId: Long?) {
        val session = conversationSession
        val lines = _state.value.lines
        val candidates = if (targetLineId != null) lines.filter { it.id == targetLineId } else lines
        if (session == null || candidates.isEmpty()) {
            photos.forget(uri)
            _state.update { it.copy(error = PACKAGE_PHOTO_NO_MEAL) }
            return
        }
        // La conversation attend peut-être une réponse : la photo répond à sa place.
        conversationJob?.cancel()
        speech.stop()
        pendingAnswer?.complete(null)
        pendingAnswer = null
        photoJob?.cancel()
        _state.update {
            it.copy(
                phase = AssistantPhase.ANALYSING,
                progress = PipelineStep(index = 1, total = 1, name = PACKAGE_PHOTO_STEP),
                error = null,
                photoSeen = null,
                conversation = null,
            )
        }
        photoJob = viewModelScope.launch {
            var resumed = false
            try {
                val jpeg = photos.jpeg(uri)
                if (jpeg == null || jpeg.isEmpty()) {
                    _state.update { it.copy(error = PHOTO_UNREADABLE) }
                    return@launch
                }
                val started = System.currentTimeMillis()
                val reading = try {
                    withContext(Dispatchers.IO) {
                        // À l'aveugle : le modèle ne connaît pas le repas (il inventait sinon
                        // l'aliment de la liste, essai du 10/11/2026).
                        packageReader.read(session.apiKey, jpeg)
                    }
                } catch (error: MistralException) {
                    _state.update { it.copy(error = error.message) }
                    return@launch
                }
                Log.i(
                    "MealAnnouncerTiming",
                    "Photo de l'emballage lue en ${System.currentTimeMillis() - started} ms : $reading",
                )
                // D'abord la voix : ce que le modèle a vu, avant tout calcul.
                reading.description?.let { seen ->
                    _state.update { it.copy(photoSeen = seen) }
                    say(seen)
                }
                val unit = packageUnit(reading)
                val offProduct = pipeline.lookupPackageInOff(
                    barcode = reading.barcode,
                    productName = reading.product,
                    visibleFood = reading.visibleFood,
                )?.let { found ->
                    // Si l'emballage donne le poids ou les glucides d'une pièce que la fiche locale
                    // n'avait pas encore, on enrichit directement la fiche.
                    when {
                        found.carbsPerUnit == null && unit?.carbsPerUnit != null ->
                            found.copy(carbsPerUnit = unit.carbsPerUnit)
                        found.defaultPortionGrams == null && reading.pieceGrams != null ->
                            found.copy(
                                defaultPortionGrams = reading.pieceGrams,
                                servingLabel = found.servingLabel ?: "1 pièce (${formatQuantityText(reading.pieceGrams!!)} g)",
                            )
                        else -> found
                    }
                }
                if (offProduct != null) {
                    Log.i(
                        "MealAnnouncerTiming",
                        "Produit retrouvé dans Open Food Facts depuis la photo : ${offProduct.displayName()} " +
                            "(code=${offProduct.barcode}, glucides=${offProduct.carbsPer100g} g/100 g, portion=${offProduct.defaultPortionGrams} g)",
                    )
                }
                val line = PhotoLineMatcher.match(reading, candidates)
                    ?: offProduct?.let { found ->
                        PhotoLineMatcher.match(
                            reading.copy(
                                product = listOfNotNull(reading.product, found.displayName()).joinToString(" "),
                                visibleFood = reading.visibleFood ?: found.name,
                            ),
                            candidates,
                        )
                    }
                if (line == null) {
                    // Aucune ligne existante ne correspond, mais la photo montre un produit
                    // d'emballage ou un code-barres identifiable : on l'ajoute au repas en cours.
                    val newFoodName = offProduct?.displayName() ?: reading.product
                    val carbs = reading.carbsPer100g
                    if (targetLineId == null && (offProduct != null || (newFoodName != null && carbs != null))) {
                        if (offProduct != null) {
                            conversation.rememberProductFromPhoto(
                                session = session,
                                line = null,
                                product = offProduct,
                                spokenName = reading.product ?: reading.visibleFood,
                                unit = unit,
                            )
                        }
                        val utterance = buildString {
                            append("J'ajoute au repas le produit photographié : « ")
                            append(newFoodName)
                            append(" »")
                            reading.barcode?.let { append(" (code-barres ").append(it).append(')') }
                            unit?.unitDetail?.let { append(" (").append(it).append(')') }
                            if (reading.eaten != null) {
                                append(". Quantité mangée : ").append(reading.eaten).append('.')
                            } else {
                                append('.')
                            }
                        }
                        resumed = true
                        resumeConversation(utterance)
                        return@launch
                    }
                    _state.update { it.copy(error = PACKAGE_PHOTO_NO_MATCH) }
                    say(PACKAGE_PHOTO_NO_MATCH)
                    return@launch
                }
                var learned = false
                if (offProduct != null) {
                    learned = conversation.rememberProductFromPhoto(
                        session = session,
                        line = line,
                        product = offProduct,
                        spokenName = reading.product ?: reading.visibleFood,
                        unit = unit,
                    )
                }
                val carbs = reading.carbsPer100g
                if (line.product == null && offProduct == null && carbs != null) {
                    // Pas de fiche Open Food Facts : le tableau photographié remplace l'estimation.
                    val fromPhoto = WebCarbEstimate(
                        name = (reading.product ?: line.name) + " (tableau nutritionnel photographié)",
                        carbsPer100g = carbs,
                        sourceUrl = null,
                        carbsPerUnit = unit?.carbsPerUnit,
                        unitGrams = unit?.unitGrams,
                        unitDetail = unit?.unitDetail,
                    )
                    learned = conversation.rememberCarbsFromPhoto(session, line, fromPhoto) || learned
                }
                if ((line.product != null || offProduct != null) && unit != null) {
                    learned = conversation.rememberUnitFromPhoto(
                        session,
                        if (offProduct != null) line.copy(product = offProduct) else line,
                        unit,
                    ) || learned
                }
                if (!learned && reading.eaten == null) {
                    _state.update { it.copy(error = PACKAGE_PHOTO_NOTHING) }
                    say(PACKAGE_PHOTO_NOTHING)
                    return@launch
                }
                val resolvedName = offProduct?.displayName() ?: line.name
                val utterance = buildString {
                    append("Photo de l'aliment #").append(line.id).append(" « ").append(resolvedName).append(" »")
                    reading.description?.let { append(" : ").append(it) }
                    unit?.unitDetail?.let { append(" (").append(it).append(')') }
                    reading.eaten?.let { append(" Sur la photo, mangé : ").append(it).append('.') }
                }
                resumed = true
                resumeConversation(utterance)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = PHOTO_UNREADABLE) }
            } finally {
                photos.forget(uri)
                if (!resumed) {
                    _state.update {
                        if (it.phase == AssistantPhase.ANALYSING) {
                            it.copy(phase = AssistantPhase.READY, progress = null)
                        } else {
                            it
                        }
                    }
                }
            }
        }
    }

    /**
     * Ce que la photo permet de chiffrer pour une pièce, au format de la fiche « par pièce » du
     * web (seuls [WebCarbEstimate.carbsPerUnit], [WebCarbEstimate.unitGrams] et le détail
     * affiché servent), ou `null` si elle ne dit rien d'utilisable.
     */
    private fun packageUnit(reading: PackageReading): WebCarbEstimate? {
        if (!reading.knowsUnit) return null
        // La colonne « par portion » passe avant tout calcul (point 7 de la demande du
        // 14/11/2026) : l'emballage donne déjà les glucides d'une portion déterminée, la lire
        // évite une division inutile — et une erreur entre deux colonnes voisines.
        val portionCarbs = reading.carbsPerPortion
        if (portionCarbs != null && reading.carbsPerUnit == null) {
            val weight = reading.portionGrams
            val label = reading.portionLabel?.takeIf { it.isNotBlank() } ?: "une portion"
            val detail = when {
                weight != null && reading.readOnPackage ->
                    "$label : ${formatQuantityText(portionCarbs)} g de glucides pour " +
                        "${formatQuantityText(weight)} g (colonne « par portion » lue sur l'emballage)"
                weight != null ->
                    "$label : ${formatQuantityText(portionCarbs)} g de glucides pour " +
                        "${formatQuantityText(weight)} g (lu sur l'emballage)"
                else ->
                    "$label : ${formatQuantityText(portionCarbs)} g de glucides " +
                        "(colonne « par portion » lue sur l'emballage)"
            }
            return WebCarbEstimate(
                name = reading.product ?: "photo de l'emballage",
                carbsPer100g = reading.carbsPer100g ?: 0.0,
                sourceUrl = null,
                carbsPerUnit = portionCarbs,
                unitGrams = null,
                unitDetail = detail,
            )
        }
        val grams = reading.pieceGrams
        val perUnit = reading.carbsPerUnit?.takeIf { reading.readOnPackage || reading.unitGrams == null }
        val detail = when {
            perUnit != null -> "glucides d'une pièce lus sur l'emballage : ${formatQuantityText(perUnit)} g"
            grams == null -> return null
            !reading.readOnPackage -> "une pièce pèse environ ${formatQuantityText(grams)} g (estimé d'après la photo)"
            reading.unitGrams == null ->
                "une pièce pèse ${formatQuantityText(grams)} g (paquet de ${formatQuantityText(reading.netGrams ?: 0.0)} g " +
                    "pour ${formatQuantityText(reading.piecesPerPack ?: 0.0)} pièces, lu sur l'emballage)"
            else -> "une pièce pèse ${formatQuantityText(grams)} g (lu sur l'emballage)"
        }
        return WebCarbEstimate(
            name = reading.product ?: "photo de l'emballage",
            carbsPer100g = reading.carbsPer100g ?: 0.0,
            sourceUrl = null,
            carbsPerUnit = perUnit,
            unitGrams = if (perUnit == null) grams else null,
            unitDetail = detail,
        )
    }

    /**
     * Aucun appareil photo utilisable : on efface le fichier vide et on le dit à l'écran,
     * plutôt que de laisser un bouton sans effet.
     */
    fun onCameraUnavailable(uri: Uri?) {
        precisionLineId = null
        uri?.let(photos::forget)
        photoJob?.cancel()
        photoJob = null
        _state.update { it.copy(photo = null, error = CAMERA_UNAVAILABLE) }
    }

    /** Écarte la proposition : rien n'entre dans le repas. */
    fun discardPhoto() {
        photoJob?.cancel()
        photoJob = null
        _state.update { it.copy(photo = null) }
    }

    /**
     * Accepte la proposition : les lignes rejoignent le repas, avec le même chemin qu'une
     * dictée à partir de là (portions, total, écriture dans CamAPS).
     */
    fun confirmPhoto() {
        val proposal = _state.value.photo ?: return
        if (!proposal.canAdd) return
        _state.update { state ->
            val dernier = state.lines.maxOfOrNull { it.id } ?: 0L
            val ajoutees = proposal.lines.mapIndexed { index, line ->
                line.copy(id = dernier + index + 1)
            }
            state.copy(
                lines = state.lines + ajoutees,
                photo = null,
                camaps = null,
                phase = AssistantPhase.READY,
            )
        }
    }

    /**
     * Lit une photo d'assiette et prépare des lignes **proposées**.
     *
     * La photo part chez Mistral avec, pour seul contexte, ce que l'utilisateur a déjà écrit ou
     * dit : aucune donnée de santé, aucun historique. Les aliments reviennent nommés, puis
     * suivent exactement la même recherche que ceux d'une phrase dictée.
     */
    private fun analysePhoto(uri: Uri) {
        if (_state.value.photo?.busy == true) return
        photoJob?.cancel()
        _state.update { it.copy(photo = PhotoProposal(busy = true), error = null) }
        photoJob = viewModelScope.launch {
            try {
                val jpeg = photos.jpeg(uri)
                if (jpeg == null || jpeg.isEmpty()) {
                    _state.update { it.copy(photo = PhotoProposal(error = PHOTO_UNREADABLE)) }
                    return@launch
                }
                val key = withContext(Dispatchers.IO) { keys.read() }
                if (key.isNullOrBlank()) {
                    _state.update {
                        it.copy(photo = PhotoProposal(error = PHOTO_NEEDS_KEY), missingKey = true)
                    }
                    return@launch
                }
                val hint = _state.value.transcript.trim().ifBlank { null }
                val reading = try {
                    withContext(Dispatchers.IO) { plateReader.read(key, jpeg, hint) }
                } catch (error: MistralException) {
                    _state.update { it.copy(photo = PhotoProposal(error = error.message)) }
                    return@launch
                }
                Log.i(HOME_TAG, "Photo d'assiette lue : ${reading.description} — ${reading.foods}")
                // Ce que la photo a montré est dit à voix haute pendant que les valeurs se cherchent.
                reading.description?.let { seen ->
                    _state.update { state -> state.copy(photo = state.photo?.copy(description = seen)) }
                    viewModelScope.launch { say(seen) }
                }
                if (reading.foods.isEmpty()) {
                    _state.update {
                        it.copy(
                            photo = PhotoProposal(
                                rejected = reading.rejected,
                                error = "La photo ne montre aucun aliment reconnaissable.",
                                description = reading.description,
                            ),
                        )
                    }
                    return@launch
                }

                // Un poids estimé devient une quantité en grammes ; un aliment sans poids
                // estimé garde une quantité vide et sera demandé comme les autres.
                val foods = reading.foods.map { food ->
                    DetectedFood(
                        name = food.name,
                        quantity = food.grams,
                        unit = MealUnit.GRAM,
                        rawText = food.name,
                        barcode = food.barcode,
                    )
                }
                val resolved = pipeline.resolveLines(key, foods) { step ->
                    _state.update { state -> state.copy(photo = state.photo?.copy(step = step)) }
                }
                _state.update {
                    it.copy(
                        phase = AssistantPhase.READY,
                        photo = PhotoProposal(
                            lines = resolved.lines,
                            rejected = reading.rejected,
                            warnings = resolved.warnings,
                            description = reading.description,
                        ),
                        camaps = null,
                    )
                }
            } catch (error: CancellationException) {
                // « Recommencer » ou une nouvelle photo : ce n'est pas un échec, on se tait.
                throw error
            } catch (error: Exception) {
                // Un décodage d'image peut échouer de façons très diverses (fichier tronqué,
                // format exotique) : l'écran le dit, l'application ne tombe pas.
                _state.update { it.copy(photo = PhotoProposal(error = PHOTO_UNREADABLE)) }
            } finally {
                // Le fichier temporaire d'une photo prise par l'application disparaît dès qu'elle
                // a été lue : une photo d'assiette n'a pas à rester sur le téléphone.
                photos.forget(uri)
                _state.update { state -> state.copy(photo = state.photo?.copy(busy = false)) }
            }
        }
    }

    /**
     * Une portion en images touchée (`docs/PORTIONS.md`) : la ligne passe à ce poids, en grammes,
     * comme si l'utilisateur l'avait tapé. Si une question de portion ou une confirmation CamAPS
     * était en cours, elle prend immédiatement en compte ce choix.
     */
    fun pickPortion(id: Long, grams: Double) {
        val text = formatQuantityText(grams)
        onUnitChange(id, MealUnit.GRAM)
        onQuantityChange(id, text)
        conversationSession?.let { session ->
            session.lastLines = _state.value.lines
        }
        logMeal("Portion choisie en image : #$id → $text g")

        val wasInConversation = conversationJob?.isActive == true
        val wasInCamapsConfirm = camapsConfirmJob?.isActive == true
        if (wasInConversation) {
            val remainingMissing = _state.value.lines.any { it.needsPortion() }
            if (!remainingMissing) {
                conversationJob?.cancel()
                conversationJob = null
                speech.stop()
                pendingAnswer?.complete(null)
                pendingAnswer = null
                _state.update { it.copy(conversation = null, progress = null, phase = AssistantPhase.READY) }
                viewModelScope.launch {
                    if (concludeConversation()) writeToCamaps()
                }
            } else {
                val lineName = _state.value.lines.firstOrNull { it.id == id }?.name ?: "cet aliment"
                speech.stop()
                val waiting = pendingAnswer
                if (waiting != null) {
                    waiting.complete("$text grammes")
                } else {
                    resumeConversation("Pour $lineName : $text grammes.")
                }
            }
        } else if (wasInCamapsConfirm) {
            camapsConfirmJob?.cancel()
            camapsConfirmJob = null
            speech.stop()
            pendingAnswer?.complete(null)
            pendingAnswer = null
            _state.update { it.copy(camapsConfirm = null, phase = AssistantPhase.READY) }
            if (chainsAfterTotal()) {
                writeToCamaps()
            }
        }
    }

    fun onQuantityChange(id: Long, text: String) {
        _state.update { state ->
            state.copy(
                lines = state.lines.map { line ->
                    if (line.id == id) line.copy(quantityText = text) else line
                },
                camaps = null,
            )
        }
    }

    /**
     * Ouvre le dialogue de portion : l'assistant demande les quantités **une par une**, à voix
     * haute, et retient ce qu'on lui répond.
     *
     * Rien ne se déclenche sans ce geste : ouvrir le micro tout seul, au moment où les lignes
     * s'affichent, surprendrait l'utilisateur et enregistrerait la première phrase venue.
     */
    fun startPortionDialogue() {
        if (dialogueJob?.isActive == true) return
        val state = _state.value
        if (state.phase != AssistantPhase.READY) return
        if (state.lines.none { it.needsPortion() }) return
        dialogueJob = viewModelScope.launch { runDialogue() }
    }

    /**
     * Coupe le dialogue : plus de question, plus de micro.
     *
     * Les portions déjà dites restent : arrêter de parler n'efface pas ce qui a été compris.
     */
    fun stopDialogue() {
        dialogueJob?.cancel()
        dialogueJob = null
        speech.stop()
        pendingAnswer?.complete(null)
        pendingAnswer = null
        _state.update { it.copy(dialogue = null, phase = AssistantPhase.READY) }
    }

    /**
     * La boucle du dialogue : chaque aliment sans portion est demandé, dans l'ordre de la
     * phrase, jusqu'à ce qu'il n'y ait plus rien à demander.
     */
    private suspend fun runDialogue() {
        val key = withContext(Dispatchers.IO) { keys.read() }
        // Un aliment qu'on n'a pas pu chiffrer ne revient pas en boucle : il reste à l'écran,
        // visiblement sans portion.
        val considered = mutableSetOf<Long>()
        try {
            while (true) {
                val line = _state.value.lines
                    .firstOrNull { it.needsPortion() && it.id !in considered } ?: break
                considered += line.id
                askForPortion(line, key)
            }
            say(
                PortionQuestions.finished(_state.value.total, _state.value.missingPortions),
            )
        } finally {
            speech.stop()
            pendingAnswer = null
            _state.update { it.copy(dialogue = null, phase = AssistantPhase.READY) }
        }
    }

    /**
     * Demande la portion d'un aliment, en reposant la question tant qu'elle n'a pas de
     * réponse chiffrable. Rend vrai si l'aliment a fini par être chiffré.
     */
    private suspend fun askForPortion(line: AssistantLine, key: String?): Boolean {
        var question = PortionQuestions.forLine(line)
        var silences = 0
        for (round in 0 until MAX_DIALOGUE_ROUNDS) {
            val heard = askAndListen(line, question, round)
            if (heard.isNullOrBlank()) {
                // Deux silences d'affilée : insister une troisième fois ne ferait qu'agacer.
                // L'aliment reste à l'écran, visiblement sans portion.
                silences++
                if (silences >= 2) return false
                question = PortionQuestions.notUnderstood()
                continue
            }
            silences = 0
            _state.update {
                it.copy(
                    phase = AssistantPhase.READY,
                    dialogue = it.dialogue?.copy(heard = heard, thinking = true),
                )
            }
            when (val reading = readPortion(line, heard, key)) {
                is PortionReading.Grams -> {
                    applyPortion(line.id, reading.grams, reading.detail)
                    val updated = _state.value.lines.firstOrNull { it.id == line.id } ?: line
                    val carbs = (updated.carbResult() as? CarbResult.Computed)?.carbs
                    say(PortionQuestions.noted(updated, carbs))
                    return true
                }

                is PortionReading.Ask -> question = reading.question

                is PortionReading.Unclear -> question = PortionQuestions.notUnderstood()
            }
        }
        return false
    }

    /**
     * Pose [question] à voix haute, puis écoute la réponse.
     *
     * La pause après la question n'est pas décorative : sans elle, le micro s'ouvre sur la
     * fin de la phrase de l'assistant et se reconnaît lui-même.
     */
    private suspend fun askAndListen(
        line: AssistantLine,
        question: String,
        round: Int,
    ): String? {
        _state.update {
            it.copy(
                phase = AssistantPhase.SPEAKING,
                dialogue = PortionDialogue(
                    lineId = line.id,
                    lineName = line.name,
                    question = question,
                    rounds = round,
                ),
            )
        }
        if (home != null) {
            _state.update {
                it.copy(phase = AssistantPhase.LISTENING, dialogue = it.dialogue?.copy(listening = true))
            }
            val atHome = askAtHome(question)
            _state.update { it.copy(dialogue = it.dialogue?.copy(listening = false)) }
            if (atHome is HomeAsk.Done) return atHome.heard
        }
        say(question)
        delay(BREATH_MS)

        // Un téléphone sans reconnaissance vocale n'est pas une impasse : on attend la
        // réponse écrite au micro de l'écran, qui passe par Voxtral.
        if (!speech.available) return awaitWrittenAnswer(question)

        _state.update {
            it.copy(
                phase = AssistantPhase.LISTENING,
                dialogue = it.dialogue?.copy(listening = true),
            )
        }
        val heard = speech.listen { partial ->
            _state.update { state ->
                state.copy(dialogue = state.dialogue?.copy(heard = partial))
            }
        }
        _state.update { it.copy(dialogue = it.dialogue?.copy(listening = false)) }
        if (!heard.isNullOrBlank()) return heard
        return awaitWrittenAnswer(question)
    }

    /**
     * Attend une réponse passée par le micro de l'écran.
     *
     * Le dialogue se met en pause plutôt que de continuer : continuer sans réponse ferait
     * croire que la question a été traitée.
     */
    private suspend fun awaitWrittenAnswer(question: String): String? {
        _state.update {
            it.copy(
                phase = AssistantPhase.READY,
                dialogue = it.dialogue?.copy(
                    question = question,
                    listening = false,
                    awaitingMic = true,
                ),
            )
        }
        val answer = CompletableDeferred<String?>()
        pendingAnswer = answer
        return try {
            withTimeoutOrNull(MIC_ANSWER_TIMEOUT_MS) { answer.await() }
        } finally {
            pendingAnswer = null
        }
    }

    /**
     * Comprend une réponse : l'analyseur déterministe d'abord, le modèle pour ce qui résiste.
     *
     * L'ordre est important. « 150 grammes », « deux biscuits » ou « un tiers du paquet » se
     * lisent sans réseau et sans modèle, avec un calcul qu'on peut afficher. Le modèle n'entre
     * en jeu que sur les tournures que l'analyseur ne sait pas lire — et sa réponse passe par
     * les mêmes bornes.
     */
    private suspend fun readPortion(
        line: AssistantLine,
        heard: String,
        key: String?,
    ): PortionReading {
        val context = line.portionContext()
        val local = PortionParser.read(heard, context)
        if (local !is PortionReading.Unclear) return local
        if (key.isNullOrBlank()) return local

        val estimate = try {
            withContext(Dispatchers.IO) {
                interpreter.interpret(
                    apiKey = key,
                    food = line.name,
                    servingGrams = context.servingGrams,
                    servingLabel = context.servingLabel,
                    packageGrams = context.packageGrams,
                    answer = heard,
                )
            }
        } catch (error: MistralException) {
            // Le modèle n'a pas répondu : on garde l'aveu de l'analyseur, et l'écran redira
            // quoi répondre. Une question de portion ne justifie pas d'alerte.
            return local
        }

        val grams = estimate.grams
        val question = estimate.question
        val compris = estimate.understood.ifBlank { heard }
        return when {
            grams != null -> PortionReading.Grams(grams, compris, estimate.detail)
            question != null -> PortionReading.Ask(compris, question)
            else -> local
        }
    }

    /**
     * Retient une portion en grammes, avec le calcul qui y mène.
     *
     * La quantité passe en grammes et l'unité avec : c'est la seule unité que le calculateur
     * sait traiter pour tous les produits, et c'est celle que CamAPS attend.
     */
    private fun applyPortion(lineId: Long, grams: Double, detail: String) {
        _state.update { state ->
            state.copy(
                lines = state.lines.map { line ->
                    if (line.id == lineId) {
                        line.copy(
                            quantityText = formatQuantityText(grams),
                            unit = MealUnit.GRAM,
                            portionNote = detail.ifBlank { null },
                        )
                    } else {
                        line
                    }
                },
                camaps = null,
            )
        }
    }

    fun onUnitChange(id: Long, unit: MealUnit) {
        _state.update { state ->
            state.copy(
                lines = state.lines.map { line ->
                    if (line.id == id) line.copy(unit = unit) else line
                },
                camaps = null,
            )
        }
    }

    /** Fait dire le total par la voix réglée (Voxtral ou Android). */
    fun announceTotal() {
        val total = _state.value.total
        if (total.grams <= 0.0) return
        viewModelScope.launch { announcer.announceMeal(total.grams) }
    }

    /**
     * Recharge un repas favori (point 4 de la demande du 14/11/2026).
     *
     * Les lignes du favori remplacent ce qui est à l'écran, avec leurs valeurs recopiées : le
     * total est recalculé, jamais repris tel quel, et reste corrigeable au gramme.
     */
    fun loadFavourite(id: String) {
        val favorite = _state.value.favourites.firstOrNull { it.id == id } ?: return
        val lines = favorite.lines.map { line ->
            val id = favoriteLineSeq++
            AssistantLine(
                id = id,
                name = if (line.brand.isNullOrBlank()) line.name else "${line.name} (${line.brand})",
                rawText = favorite.name,
                quantityText = formatQuantityText(line.quantity),
                unit = line.unit,
                product = line.toProduct("favori"),
            )
        }
        _state.update {
            it.copy(
                lines = lines,
                phase = AssistantPhase.READY,
                camaps = null,
                recordNotice = "« ${favorite.name} » rappelé : ${favorite.total.format()} g.",
            )
        }
        viewModelScope.launch { favorites.markUsed(id) }
    }

    /** Enregistre le repas affiché comme favori, pour le réannoncer d'un geste. */
    fun saveFavourite(name: String) {
        val lines = _state.value.lines
        if (lines.isEmpty()) return
        val label = name.trim().ifBlank { lines.take(2).joinToString(" + ") { it.name } }.take(80)
        val favoriteLines = lines.map { line ->
            FavoriteMealLine(
                name = line.product?.name ?: line.name,
                brand = line.product?.brand,
                barcode = line.product?.barcode,
                quantity = line.quantity,
                unit = line.unit,
                carbsPer100g = line.product?.carbsPer100g,
                carbsPer100ml = line.product?.carbsPer100ml,
                carbsPerUnit = line.product?.carbsPerUnit,
                portionGrams = line.product?.defaultPortionGrams,
                portionMl = line.product?.defaultPortionMl,
                servingLabel = line.product?.servingLabel,
                packageGrams = line.product?.packageGrams,
            )
        }
        viewModelScope.launch {
            favorites.create(label, favoriteLines)
            _state.update { it.copy(recordNotice = "« $label » enregistré dans les favoris.") }
        }
    }

    /**
     * Écrit les grammes dans CamAPS FX.
     *
     * Deux chemins selon le réglage « Insertion automatique » :
     * - **désactivé** (défaut) : CamAPS est seulement ouvert, l'utilisateur saisit lui-même ;
     * - **activé** : un dialogue vocal demande le type, répète les glucides, et n'écrit — puis
     *   n'appuie sur « Continuer » — qu'après un « oui » franc. Rappel : « Ajouter un repas »
     *   informe la boucle fermée, il ne dose pas d'insuline.
     */
    fun writeToCamaps() {
        val state = _state.value
        if (!state.canWrite) return
        val carbs = state.camapsGrams
        val description = mealDescription(state.lines)
        if (mealRecord.config.value.liberty) {
            // Liberty : rien vers « Ajouter un repas », pas même l'ouverture de CamAPS.
            if (camapsConfirmJob?.isActive == true) return
            camapsConfirmJob = viewModelScope.launch { recordLibertyMeal(carbs, description) }
            return
        }
        if (!mealRecord.config.value.camapsAutoActive()) {
            // Le tap du bouton tient lieu de confirmation : on écrit les cibles de suivi, puis on
            // ouvre CamAPS pour la saisie manuelle.
            viewModelScope.launch {
                writeSuiviTargets(description, carbs)
                logMeal("Envoi à CamAPS (saisie à la main) : $carbs g")
                val result = camaps.writeCarbs(carbs)
                _state.update { it.copy(camaps = result) }
                logDirectDestination("CamAPS : $carbs g, saisie à la main ouverte (${result::class.simpleName})")
            }
            return
        }
        if (camapsConfirmJob?.isActive == true) return
        camapsConfirmJob = viewModelScope.launch { runCamapsConfirm(carbs, description) }
    }

    /**
     * Les cibles du mode « Suivi diabétologue », dans l'ordre du contrat (le médical en dernier).
     *
     * Aujourd'hui : le **Carnet Glooko** (journal, aucune insuline). La **note Nightscout** est
     * gelée le temps de la réécriture de la couche NS (fork NightscoutCamAPS) : elle sera branchée
     * ici quand l'API sera figée.
     *
     * Rend `true` quand une cible a réellement été écrite : c'est ce que le journal
     * d'automatisation distingue d'un arrêt à CamAPS.
     */
    private suspend fun writeSuiviTargets(description: String, carbs: Int, force: Boolean = false): Boolean {
        val config = mealRecord.config.value
        var written = false
        if ((force || config.writesGlookoCarnet()) && description.isNotBlank()) {
            val moment = GlookoCarnetMoment.forHour(LocalTime.now().hour)
            written = when (val result = glookoCarnet.addEntry(description, carbs, moment)) {
                is GlookoCarnetResult.Saved -> {
                    _state.update { it.copy(recordNotice = "Repas noté dans le Carnet Glooko.") }
                    say("Repas noté dans le Carnet Glooko.")
                    logDirectDestination("Carnet Glooko : noté ($carbs g) — $description")
                    true
                }
                is GlookoCarnetResult.Failed -> {
                    // Le masque cache les écrans de Glooko : un échec doit donc se dire, sinon
                    // l'utilisateur croit son repas noté.
                    _state.update { it.copy(recordNotice = "Carnet Glooko : ${result.message}") }
                    say("Le repas n'a pas pu être noté dans le Carnet Glooko.")
                    logDirectDestination("Carnet Glooko : échec — ${result.message}")
                    false
                }
            }
        }
        // Niveau 3, mode Gemini : le repas dans Nightscout, glucides ET description (décision du porteur
        // du projet du 23/11/2026). L'anti-doublon avec la synchro Glooko est dans sendMealWithCarbs
        // (cas inverse) et dans le glooko-worker du serveur (cas courant). La note des autres repas
        // reste gelée (`docs/CAMAPS-REPAS.md` § 6).
        if (directLog != null && config.writesNightscoutNote() && description.isNotBlank()) {
            // Verrou : tant que l'anti-doublon du glooko-worker n'est pas en production, les glucides
            // ne partent pas (ils arrivent déjà par la synchro Glooko) — la note porte le total en texte.
            val note = if (NIGHTSCOUT_MEAL_CARBS) {
                "Miam-Miam (Gemini) : $description"
            } else {
                "Miam-Miam (Gemini) : $carbs g annoncés à CamAPS — $description"
            }
            val sent = if (NIGHTSCOUT_MEAL_CARBS) {
                nightscout.sendMealWithCarbs(carbs, note, Instant.now())
            } else {
                com.najah.mealannouncer.data.nightscout.MealEntryResult(nightscout.sendNote(note, Instant.now()))
            }
            val label = when (val outcome = sent.outcome) {
                NightscoutSendOutcome.Sent -> if (sent.carbsCounted) {
                    "$carbs g envoyés avec la description"
                } else {
                    "description envoyée seule (glucides déjà comptés par la synchro Glooko ${sent.glookoMatchId.orEmpty()})"
                }
                NightscoutSendOutcome.Simulated -> "simulation, rien envoyé"
                NightscoutSendOutcome.Disabled -> "Nightscout non réglé, rien envoyé"
                is NightscoutSendOutcome.Failed -> "échec (${outcome.kind})"
                else -> outcome.toString()
            }
            Log.i(DIRECT_TAG, "Nightscout : $label")
            logDirectDestination("Nightscout : $label — $note")
            if (sent.outcome == NightscoutSendOutcome.Sent) written = true
        }
        return written
    }

    /**
     * Un total complet enchaîne-t-il tout seul ? Oui avec l'insertion automatique dans CamAPS, et
     * oui sous Liberty — où l'enchaînement ne fait que **noter** le repas (journal, Carnet Glooko,
     * Nightscout), sans jamais toucher à CamAPS.
     */
    private fun chainsAfterTotal(): Boolean {
        val config = mealRecord.config.value
        return config.liberty || config.camapsAutoActive()
    }

    /**
     * Mode Liberty (décision de l'équipe médicale du 24/11/2026) : la première partie de la séquence
     * — l'annonce des glucides dans « Ajouter un repas » de CamAPS FX — est supprimée. Le reste est
     * conservé : le journal, puis les cibles de suivi (Carnet Glooko, Nightscout) selon les réglages.
     */
    private suspend fun recordLibertyMeal(carbs: Int, description: String, forceGlooko: Boolean = false) {
        try {
            logMeal("Mode Liberty : $carbs g, rien dans CamAPS")
            logDirectDestination("CamAPS : rien envoyé (mode Liberty)")
            _state.update {
                it.copy(camaps = null, recordNotice = "Mode Liberty : rien n'est annoncé dans CamAPS.")
            }
            val wanted = suiviWanted(forceGlooko) && description.isNotBlank()
            if (!wanted) {
                journal.record(
                    carbs = carbs,
                    description = description,
                    source = AutomationSource.ASSISTANT,
                    outcome = AutomationOutcome.LIBERTY,
                    detail = "Mode Liberty : rien dans CamAPS ; suivi diabétologue coupé, rien d'autre de noté.",
                )
                say("$carbs grammes de glucides. Mode Liberty : je n'annonce rien à CamAPS.")
                return
            }
            // Le Carnet Glooko s'écrit à l'écran : il faut un téléphone déverrouillé.
            if (home != null && phoneLock.isLocked()) {
                say(HomeVoiceChannel.UNLOCK_PHONE)
                if (!awaitUnlocked()) {
                    say(HomeVoiceChannel.STILL_LOCKED)
                    journal.record(
                        carbs = carbs,
                        description = description,
                        source = AutomationSource.ASSISTANT,
                        outcome = AutomationOutcome.FAILED,
                        detail = "Mode Liberty : téléphone resté verrouillé, rien de noté.",
                    )
                    return
                }
            }
            say("$carbs grammes de glucides. Mode Liberty : rien dans CamAPS, je note le repas.")
            val tracked = writeSuiviTargets(description, carbs, force = forceGlooko)
            journal.record(
                carbs = carbs,
                description = description,
                source = AutomationSource.ASSISTANT,
                outcome = AutomationOutcome.LIBERTY,
                detail = if (tracked) {
                    "Mode Liberty : rien dans CamAPS, repas noté (Carnet Glooko / Nightscout)."
                } else {
                    "Mode Liberty : rien dans CamAPS, et le suivi n'a pas pu être écrit."
                },
            )
        } finally {
            speech.stop()
            home = null
            _state.update { it.copy(camapsConfirm = null, phase = AssistantPhase.READY) }
        }
    }

    /** Vrai quand une cible de suivi (Carnet Glooko, ou note Nightscout en mode Gemini) attend CamAPS. */
    private fun suiviWanted(force: Boolean): Boolean {
        val config = mealRecord.config.value
        return force || config.writesGlookoCarnet() || (directLog != null && config.writesNightscoutNote())
    }

    /** Coupe le dialogue de confirmation CamAPS : plus de question, plus de micro, rien d'écrit. */
    fun stopCamapsConfirm() {
        camapsConfirmJob?.cancel()
        camapsConfirmJob = null
        home = null
        speech.stop()
        pendingAnswer?.complete(null)
        pendingAnswer = null
        _state.update { it.copy(camapsConfirm = null, phase = AssistantPhase.READY) }
    }

    /**
     * Le dialogue de confirmation : demander le type, répéter les glucides, puis écrire —
     * jamais sans un « oui ».
     */
    private suspend fun runCamapsConfirm(
        carbs: Int,
        description: String,
        presetType: CamapsMealType? = null,
        forceGlooko: Boolean = false,
    ) {
        if (mealRecord.config.value.liberty) {
            // Même un total dicté (« annonce 25 grammes ») n'entre plus dans CamAPS sous Liberty.
            recordLibertyMeal(carbs, description, forceGlooko)
            return
        }
        var correction: String? = null
        var pendingCommand: MealCommand? = null
        var endedByCancel = false
        try {
            // Énoncé UNIQUE : le repas compris + le total + la demande du type. Répondre le type
            // vaut confirmation — pas de seconde question qui répéterait tout.
            // Le détail ligne par ligne, glucides compris : on entend d'où vient le total avant de
            // répondre le type — c'est ce qui aurait arrêté le thé à 123 g.
            val summary = spokenDetail(_state.value.lines)
            val answer = if (presetType != null) TypeAnswer.Type(presetType) else askMealType(carbs, summary)
            when (answer) {
                is TypeAnswer.Correction -> {
                    // « Non, ce n'est pas ce produit » : rien n'est écrit, la conversation reprend.
                    correction = answer.heard
                    return
                }
                TypeAnswer.CancelledAll -> {
                    endedByCancel = true
                    cancelAllMeal()
                    return
                }
                TypeAnswer.RemovedAll -> return
                is TypeAnswer.AddMore -> {
                    pendingCommand = answer.command
                    return
                }
                else -> Unit
            }
            val effectiveCarbs = _state.value.camapsGrams.takeIf { it > 0 && _state.value.lines.isNotEmpty() } ?: carbs
            val effectiveDescription = mealDescription(_state.value.lines).ifBlank { description }
            val type = (answer as? TypeAnswer.Type)?.type
            if (type == null) {
                // Après plusieurs essais sans comprendre : on propose la photo, on ne bloque pas.
                say(CamapsVoice.photoSuggestion())
                _state.update {
                    it.copy(camaps = CamapsWriteResult.Failed("Type non compris. Photographiez l'assiette ou recommencez."))
                }
                return
            }
            // CamAPS d'abord (l'essentiel), puis — seulement si l'utilisateur a confirmé dans CamAPS — le
            // Carnet Glooko, écrit sous masque. Glooko ne vole jamais l'écran avant la confirmation.
            if (home != null && phoneLock.isLocked()) {
                say(HomeVoiceChannel.UNLOCK_PHONE)
                if (!awaitUnlocked()) {
                    say(HomeVoiceChannel.STILL_LOCKED)
                    _state.update {
                        it.copy(camaps = CamapsWriteResult.Failed("Téléphone resté verrouillé : rien n'a été écrit dans CamAPS."))
                    }
                    return
                }
            }
            if (presetType != null) {
                say("$effectiveCarbs grammes, ${CamapsScreen.MEAL_TYPE_LABELS.getValue(presetType)}. J'ouvre CamAPS.")
            }
            logMeal("Envoi à CamAPS : $effectiveCarbs g")
            val result = camaps.announceMeal(CamapsMealRequest(carbs = effectiveCarbs, type = type, confirm = true))
            _state.update { it.copy(camaps = result) }
            logDirectDestination(
                if (result is CamapsWriteResult.Entered) {
                    "CamAPS : $effectiveCarbs g saisis (${CamapsScreen.MEAL_TYPE_LABELS.getValue(type)}), en attente de Confirmer"
                } else {
                    "CamAPS : non atteint — ${(result as? CamapsWriteResult.Failed)?.message ?: result}"
                },
            )
            if (result !is CamapsWriteResult.Entered) {
                // Un obstacle (alerte de CamAPS, écran inattendu) : rien n'est écrit. On le dit, avec
                // la phrase qui reprend la main — le repas compris est gardé pour le Carnet.
                unsentMeal = effectiveDescription to System.currentTimeMillis()
                Log.w(HOME_TAG, "CamAPS non atteint : $result")
                journal.record(
                    carbs = effectiveCarbs,
                    description = effectiveDescription,
                    source = AutomationSource.ASSISTANT,
                    outcome = AutomationOutcome.FAILED,
                    detail = (result as? CamapsWriteResult.Failed)?.message ?: "CamAPS non atteint",
                )
                say(
                    "Je n'ai rien pu saisir : CamAPS affiche un message. Règle-le, puis dis : " +
                        "Okay Nabu, annonce $effectiveCarbs grammes.",
                )
                return
            }
            unsentMeal = null
            val glooko = forceGlooko || mealRecord.config.value.writesGlookoCarnet()
            if (!suiviWanted(forceGlooko) || effectiveDescription.isBlank()) {
                // Arrêt volontaire à CamAPS : le suivi est coupé, et le journal doit le montrer
                // plutôt que de laisser croire à un repas mené jusqu'au bout.
                journal.record(
                    carbs = effectiveCarbs,
                    description = effectiveDescription,
                    source = AutomationSource.ASSISTANT,
                    outcome = AutomationOutcome.CAMAPS_ONLY,
                    detail = "Suivi diabétologue coupé : rien n'est noté après CamAPS.",
                )
                say("$effectiveCarbs grammes saisis dans CamAPS. Appuie sur Confirmer.")
                return
            }
            say(
                if (glooko) {
                    "$effectiveCarbs grammes saisis dans CamAPS. Appuie sur Confirmer, je noterai ensuite le repas dans le Carnet Glooko."
                } else {
                    "$effectiveCarbs grammes saisis dans CamAPS. Appuie sur Confirmer, j'enverrai ensuite la note à Nightscout."
                },
            )
            _state.update {
                it.copy(camapsConfirm = null, recordNotice = "En attente de ta confirmation dans CamAPS…")
            }
            when (camaps.awaitMealOutcome(CAMAPS_CONFIRM_WAIT_MS)) {
                CamapsMealOutcome.CONFIRMED -> {
                    logDirectDestination("CamAPS : confirmé")
                    val tracked = writeSuiviTargets(effectiveDescription, effectiveCarbs, force = forceGlooko)
                    journal.record(
                        carbs = effectiveCarbs,
                        description = effectiveDescription,
                        source = AutomationSource.ASSISTANT,
                        outcome = if (tracked) AutomationOutcome.COMPLETE else AutomationOutcome.CAMAPS_ONLY,
                        detail = if (tracked) {
                            "Confirmé dans CamAPS, repas noté dans le Carnet Glooko."
                        } else {
                            "Confirmé dans CamAPS, Carnet Glooko non écrit."
                        },
                    )
                }
                CamapsMealOutcome.CANCELLED -> {
                    logDirectDestination("CamAPS : annulé dans CamAPS, rien noté ailleurs")
                    journal.record(
                        carbs = effectiveCarbs,
                        description = effectiveDescription,
                        source = AutomationSource.ASSISTANT,
                        outcome = AutomationOutcome.CANCELLED,
                        detail = "Annulé dans CamAPS.",
                    )
                    _state.update { it.copy(recordNotice = "Annulé dans CamAPS : rien n'est noté dans le Carnet Glooko.") }
                    say("Annulé dans CamAPS. Je ne note rien dans le Carnet.")
                }
                CamapsMealOutcome.TIMEOUT, CamapsMealOutcome.UNKNOWN -> {
                    logDirectDestination("CamAPS : confirmation non vue, rien noté ailleurs")
                    journal.record(
                        carbs = effectiveCarbs,
                        description = effectiveDescription,
                        source = AutomationSource.ASSISTANT,
                        outcome = AutomationOutcome.CAMAPS_ONLY,
                        detail = "Confirmation dans CamAPS non observée.",
                    )
                    _state.update {
                        it.copy(recordNotice = "Confirmation CamAPS non vue : le Carnet Glooko n'a pas été écrit.")
                    }
                }
            }
        } finally {
            speech.stop()
            pendingAnswer = null
            if (!endedByCancel) {
                if (correction == null && pendingCommand == null) home = null
                _state.update { it.copy(camapsConfirm = null, phase = AssistantPhase.READY) }
                correction?.let { resumeConversation(it) }
                pendingCommand?.let { cmd -> viewModelScope.launch { executeMealCommand(cmd, apiKey = null) } }
            }
        }
    }

    /** Attend que l'utilisateur déverrouille le téléphone ; faux après [UNLOCK_WAIT_MS]. */
    private suspend fun awaitUnlocked(): Boolean {
        _unlockWanted.value = true
        try {
            return withTimeoutOrNull(UNLOCK_WAIT_MS) {
                while (phoneLock.isLocked()) delay(UNLOCK_POLL_MS)
                true
            } ?: false
        } finally {
            _unlockWanted.value = false
        }
    }

    /** « thé infusé nature : 0 grammes, sucre en morceaux : 16,5 grammes » — ce que l'on dit avant CamAPS. */
    private fun spokenDetail(lines: List<AssistantLine>): String = lines
        .filter { it.name.isNotBlank() }
        .joinToString(", ") { "${it.name} : ${it.carbs.format()} grammes" }

    /** La réponse à la question du type : un type, une contestation, une commande, ou rien de compris. */
    private sealed interface TypeAnswer {
        data class Type(val type: CamapsMealType) : TypeAnswer
        data class Correction(val heard: String) : TypeAnswer
        data object CancelledAll : TypeAnswer
        data object RemovedAll : TypeAnswer
        data class AddMore(val command: MealCommand.AddFood) : TypeAnswer
        data object NotUnderstood : TypeAnswer
    }

    /**
     * Demande le type de repas, avec une compréhension permissive et des re-réponses : si le type
     * n'est pas compris, on repose **juste** la question (pas tout le repas), jusqu'à
     * [MAX_TYPE_ROUNDS] fois. Au-delà, l'appelant propose la photo. Une contestation (« non, ce
     * n'est pas ce produit ») est reconnue **avant** le type et rendue telle quelle.
     */
    private suspend fun askMealType(carbs: Int, summary: String): TypeAnswer {
        var currentCarbs = carbs
        var currentSummary = summary
        var round = 0
        while (round < MAX_TYPE_ROUNDS) {
            val question = if (round == 0) {
                CamapsVoice.announceAndAsk(currentSummary, currentCarbs)
            } else {
                CamapsVoice.typeNotUnderstood()
            }
            val heard = confirmAskAndListen(currentCarbs, question, CamapsConfirmStep.TYPE, null).orEmpty()
            when (val cmd = MealCommand.parse(heard)) {
                MealCommand.CancelAll -> return TypeAnswer.CancelledAll
                MealCommand.RemoveLastFood -> {
                    val removed = dropLastLine()
                    if (removed == null || _state.value.lines.isEmpty() || _state.value.camapsGrams <= 0) {
                        val msg = if (removed != null) {
                            "J'ai retiré ${removed.name}. Le repas est vide."
                        } else {
                            EMPTY_REMOVE_MESSAGE
                        }
                        say(msg)
                        return TypeAnswer.RemovedAll
                    }
                    say("J'ai retiré ${removed.name}.")
                    currentCarbs = _state.value.camapsGrams
                    currentSummary = spokenDetail(_state.value.lines)
                    round = 0
                    continue
                }
                is MealCommand.AddFood -> return TypeAnswer.AddMore(cmd)
                null -> Unit
            }
            // La contestation d'abord : elle peut contenir le mot « repas ».
            if (conversationSession != null && MealCorrection.looksLikeCorrection(heard)) {
                return TypeAnswer.Correction(heard)
            }
            CamapsVoice.matchMealType(heard)?.let { return TypeAnswer.Type(it) }
            round++
        }
        return TypeAnswer.NotUnderstood
    }

    /**
     * Pose [question] à voix haute puis écoute — par la reconnaissance du téléphone, ou, à
     * défaut, par le micro de l'écran (dictée Voxtral). Même grammaire que le dialogue de portion.
     */
    private suspend fun confirmAskAndListen(
        carbs: Int,
        question: String,
        step: CamapsConfirmStep,
        type: CamapsMealType?,
    ): String? {
        _state.update {
            it.copy(
                phase = AssistantPhase.SPEAKING,
                camapsConfirm = CamapsConfirmDialogue(
                    carbs = carbs,
                    question = question,
                    step = step,
                    chosenType = type,
                ),
            )
        }
        if (home != null) {
            _state.update {
                it.copy(phase = AssistantPhase.LISTENING, camapsConfirm = it.camapsConfirm?.copy(listening = true))
            }
            val answers = if (step == CamapsConfirmStep.TYPE) HomeVoiceChannel.MEAL_TYPE_ANSWERS else emptyList()
            val atHome = askAtHome(question, answers)
            _state.update { it.copy(camapsConfirm = it.camapsConfirm?.copy(listening = false)) }
            if (atHome is HomeAsk.Done) return atHome.heard
        }
        say(question)
        delay(BREATH_MS)

        if (!speech.available) return awaitWrittenConfirm(question)

        _state.update {
            it.copy(
                phase = AssistantPhase.LISTENING,
                camapsConfirm = it.camapsConfirm?.copy(listening = true),
            )
        }
        val heard = speech.listen { partial ->
            _state.update { state -> state.copy(camapsConfirm = state.camapsConfirm?.copy(heard = partial)) }
        }
        _state.update { it.copy(camapsConfirm = it.camapsConfirm?.copy(listening = false)) }
        if (!heard.isNullOrBlank()) return heard
        return awaitWrittenConfirm(question)
    }

    /** Attend la réponse passée par le micro de l'écran, quand le téléphone n'a pas entendu. */
    private suspend fun awaitWrittenConfirm(question: String): String? {
        _state.update {
            it.copy(
                phase = AssistantPhase.READY,
                camapsConfirm = it.camapsConfirm?.copy(
                    question = question,
                    listening = false,
                    awaitingMic = true,
                ),
            )
        }
        val answer = CompletableDeferred<String?>()
        pendingAnswer = answer
        return try {
            withTimeoutOrNull(MIC_ANSWER_TIMEOUT_MS) { answer.await() }
        } finally {
            pendingAnswer = null
        }
    }

    /**
     * Quitte l'écran sans perdre le repas en cours.
     *
     * Seul le micro est coupé : une dictée abandonnée ne doit ni partir chez Mistral ni
     * laisser un enregistrement tourner en arrière-plan.
     */
    fun stopRecording() {
        if (_state.value.phase != AssistantPhase.RECORDING) return
        recordingJob?.cancel()
        recordingJob = null
        recorder.cancel()
        _state.update { it.copy(phase = AssistantPhase.IDLE) }
    }

    /** Recommence un repas : la phrase, les lignes et le dialogue repartent de zéro. */
    fun reset() {
        recorder.cancel()
        recordingJob?.cancel()
        recordingJob = null
        dialogueJob?.cancel()
        dialogueJob = null
        photoJob?.cancel()
        photoJob = null
        camapsConfirmJob?.cancel()
        camapsConfirmJob = null
        conversationJob?.cancel()
        conversationJob = null
        conversationSession = null
        directMessages = null
        directLog = null
        directLookedUp.clear()
        directOffCandidates.clear()
        directChosenBarcodes.clear()
        pendingDirectPhotos.forEach(photos::forget)
        pendingDirectPhotos.clear()
        utensilHintSaid = false
        appendNextInput = false
        home = null
        speech.stop()
        pendingAnswer?.complete(null)
        pendingAnswer = null
        _state.value = AssistantUiState(geminiDirect = mealRecord.config.value.geminiDirect)
    }

    override fun onCleared() {
        recordingJob?.cancel()
        recorder.cancel()
        conversationJob?.cancel()
        dialogueJob?.cancel()
        photoJob?.cancel()
        camapsConfirmJob?.cancel()
        speech.stop()
        pendingAnswer?.complete(null)
        pendingAnswer = null
        super.onCleared()
    }

    private companion object {
        /** Un repas resté en plan plus vieux n'est plus repris par un total dicté. */
        const val UNSENT_MEAL_MAX_AGE_MS = 15 * 60_000L

        /** Journal de la voix de la maison (`adb logcat -s MealAnnouncerHome`). */
        const val HOME_TAG = "MealAnnouncerHome"

        /** Journal du mode Gemini direct (`adb logcat -s MealAnnouncerDirect`) : totaux et durées, jamais de clé. */
        const val DIRECT_TAG = "MealAnnouncerDirect"

        /** Ce qui accompagne une photo envoyée seule, comme on l'écrirait sur le site de Gemini. */
        const val DIRECT_FIRST_PHOTO = "Calcule mes glucides"
        const val DIRECT_MORE_PHOTO = "Ceci est une photo complémentaire du même repas."
        /**
         * Glucides des repas Gemini dans Nightscout (décision du porteur du projet du 23/11/2026). OUVERT depuis le
         * déploiement de l'anti-doublon du glooko-worker par l'administrateur du serveur (23/11/2026 17:57 Paris, retour
         * arrière : ns-treatments.js.bak-20261123). Fermé, seule une note (total en texte) part.
         */
        const val NIGHTSCOUT_MEAL_CARBS = true

        /** Produits cherchés au plus dans Open Food Facts pour un même repas. */
        const val MAX_PRODUCT_LOOKUPS = 4

        const val DIRECT_MORE_PHOTOS = "Ce sont des photos complémentaires du même repas."

        /** Conseil du couvert : un objet de taille connue à côté du plat sert d'échelle à Gemini. */
        const val UTENSIL_HINT =
            "Si tu peux, pose une fourchette ou une cuillère à côté du plat : elle sert d'échelle pour estimer les portions."
        const val MORE_PHOTOS_FIRST = "Photo prise. Veux-tu en prendre une autre, ou l'envoyer ?"
        const val MORE_PHOTOS_NEXT = "%d photos prises. Une autre, ou je les envoie ?"

        /** Nom de la ligne quand Gemini ne résume pas le repas. */
        const val DIRECT_LINE_NAME = "Repas estimé par Gemini"

        const val DIRECT_SILENT_WITH_TOTAL =
            "Je n'entends rien. Je garde ce total à l'écran : tu peux le valider avec le bouton ou me répondre au micro."

        /** Au-delà, on considère que l'utilisateur a oublié d'arrêter. */
        const val MAX_RECORDING_MS = 30_000L

        /** Questions posées au maximum pour un même aliment, avant de passer au suivant. */
        const val MAX_DIALOGUE_ROUNDS = 3

        /** Essais de compréhension du type de repas avant de proposer la photo. */
        const val MAX_TYPE_ROUNDS = 3

        /** Temps laissé pour déverrouiller le téléphone avant CamAPS, quand le repas vient de la maison. */
        const val UNLOCK_WAIT_MS = 120_000L
        const val UNLOCK_POLL_MS = 500L

        /** Temps laissé à l'utilisateur pour presser « Confirmer » dans CamAPS avant d'abandonner le Carnet. */
        const val CAMAPS_CONFIRM_WAIT_MS = 180_000L

        /** Échanges au plus dans une conversation de repas : au-delà, on finit à l'écran. */
        const val MAX_CONVERSATION_TURNS = 10

        /** Réponses incomprises d'affilée avant de proposer la photo. */
        const val MAX_MISUNDERSTOOD = 3

        const val CONVERSATION_FAILED =
            "Je n'arrive pas à joindre l'assistant. Tu peux compléter le repas à l'écran."
        const val CONVERSATION_CANCELLED = "D'accord, j'arrête. Rien n'a été écrit."
        const val CONVERSATION_PHOTO =
            "Je n'arrive pas à comprendre. Tu peux photographier ton assiette, ou compléter à l'écran."
        const val CONVERSATION_SILENT =
            "Je n'entends rien, je m'arrête là. Tu peux compléter le repas à l'écran."
        const val CONVERSATION_TOO_LONG =
            "On a beaucoup échangé : je m'arrête là. Vérifie le repas à l'écran."
        const val CONVERSATION_EMPTY = "Je n'ai reconnu aucun aliment. Tu peux réessayer."
        const val CONVERSATION_DOUBT = "Je ne suis pas sûr des glucides de ce repas."

        /** Au-delà de ce silence, on dit que l'on travaille. */
        const val WAIT_PHRASE_MS = 3_000L
        const val WAIT_PHRASE = "Je calcule les glucides."

        /** Réponse vocale quand l'utilisateur dit « Miam-miam » seul au téléphone ou à l'enceinte. */
        const val WAKE_PHONE_PROMPT = "Je t'écoute."
        const val WAKE_HOME_PROMPT = "Je t'écoute, quel est ton repas ?"

        /** Réponse vocale aux commandes « ajoute / rajoute », « annule le dernier aliment » et « annuler tout le repas ». */
        const val ADD_FOOD_PROMPT = "Qu'est-ce que j'ajoute ?"
        const val MEAL_CANCELLED = "Repas annulé."
        const val EMPTY_REMOVE_MESSAGE = "Il n'y a aucun aliment à retirer."

        /** Pause entre la fin d'une question et l'ouverture du micro. */
        const val BREATH_MS = 300L

        /** Attente maximale d'une réponse passée par le micro de l'écran. */
        const val MIC_ANSWER_TIMEOUT_MS = 120_000L

        /** Une photo que l'application n'a pas su lire : on le dit, on ne devine pas. */
        /** Aucune clé d'IA : l'écran dit où la régler (une seule clé, OpenRouter, décision 48). */
        const val NO_AI_KEY = "Aucune clé OpenRouter enregistrée : réglez-la dans Réglages → Intelligence artificielle."

        const val PHOTO_UNREADABLE = "Photo illisible : choisissez-en une autre."

        /** Ce qu'affiche la barre de progression pendant la lecture d'un emballage. */
        const val PACKAGE_PHOTO_STEP = "lecture de la photo de l'emballage"

        const val PACKAGE_PHOTO_NOTHING =
            "La photo ne montre pas le poids d'une pièce : poids estimé gardé. " +
                "Essaie le dos du paquet, là où sont le poids et le tableau nutritionnel."

        const val PACKAGE_PHOTO_NO_MATCH =
            "La photo ne montre aucun aliment du repas : rien n'est changé."

        const val PACKAGE_PHOTO_NO_MEAL =
            "Plus de repas en cours à préciser : redis le repas, puis photographie l'emballage."

        /**
         * Dit à la fin d'un repas dont un poids de pièce n'est qu'estimé : le repas attend à
         * l'écran, la photo peut lever le doute.
         */
        fun weightGuessedSpeech(lines: List<AssistantLine>): String {
            val parts = mutableListOf<String>()
            lines.filter { it.carbsGuessed }.takeIf { it.isNotEmpty() }?.let { guessed ->
                parts += "Je n'ai pas trouvé la fiche de " + guessed.joinToString(" ni de ") { it.name } +
                    " : ses glucides sont une moyenne estimée"
            }
            lines.filter { it.weightGuessed }.takeIf { it.isNotEmpty() }?.let { guessed ->
                parts += "Je n'ai pas trouvé le poids exact d'une pièce : " +
                    guessed.joinToString(" ; ") { "${it.name}, compté environ ${it.quantityText} grammes en tout" }
            }
            return parts.joinToString(". ") + ". " +
                "Pour être précis, photographie le dos de l'emballage avec le bouton photo, " +
                "ou valide le repas tel quel."
        }

        /**
         * La lecture d'une photo passe par OpenRouter : sans clé, il n'y a pas d'analyse. Le
         * message est celui de l'écran, sans jargon.
         */
        const val PHOTO_NEEDS_KEY =
            "Lire une photo demande la clé OpenRouter : enregistrez-la dans Réglages → Intelligence artificielle."

        /**
         * Aucun appareil photo utilisable : le bouton ne doit pas rester sans effet, et le
         * fichier vide préparé pour la prise de vue n'a plus lieu d'être.
         */
        const val CAMERA_UNAVAILABLE =
            "Aucun appareil photo utilisable sur ce téléphone : choisissez une photo dans le téléphone."
    }
}
