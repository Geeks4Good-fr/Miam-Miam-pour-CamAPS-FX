package com.najah.mealannouncer.feature.confirmation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.najah.mealannouncer.core.database.repository.MealRepository
import com.najah.mealannouncer.core.datastore.NightscoutSettings
import com.najah.mealannouncer.core.model.Carbs
import com.najah.mealannouncer.core.model.MealConfirmation
import com.najah.mealannouncer.data.nightscout.work.NightscoutScheduler
import com.najah.mealannouncer.feature.voice.VoiceAnnouncer
import com.najah.mealannouncer.integration.glookoexport.GlookoMealTrigger
import com.najah.mealannouncer.integration.glookoexport.GlookoScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Le repas à confirmer, et les aliments qui n'ont pas pu être chiffrés.
 *
 * [skipped] n'est pas décoratif : un aliment non compté doit être visible au moment de
 * confirmer. Un total incomplet annoncé sans le dire est un total faux.
 */
data class ConfirmationPayload(
    val meal: MealConfirmation,
    val skipped: List<String> = emptyList(),
)

/** Où en est l'enregistrement du repas. */
sealed interface ConfirmationUiState {

    /** Rien n'a encore été demandé. */
    data object Idle : ConfirmationUiState

    data object Saving : ConfirmationUiState

    /** Le repas est en base : l'écran peut le dire sans mentir. */
    data class Saved(val total: Carbs) : ConfirmationUiState

    data class Failed(val message: String) : ConfirmationUiState
}

/** Ce qu'il faut dire à l'utilisateur au sujet de l'envoi vers Nightscout. */
enum class NightscoutNotice {
    /** Rien à dire : rien n'a été tenté (ou l'enregistrement a échoué). */
    NONE,

    /** Le repas est en file d'attente : il partira dès que le réseau le permet. */
    QUEUED,

    /** Mode simulation : rien n'a quitté le téléphone. */
    SIMULATED,

    /** Nightscout est coupé ou incomplet : aucun envoi. */
    DISABLED,
}

/**
 * Enregistrement d'un repas confirmé.
 *
 * L'écran de confirmation est le seul endroit où un repas entre dans l'historique : c'est
 * un geste volontaire, jamais automatique. Le ViewModel porte aussi le repas en cours, de
 * sorte qu'une rotation de l'écran ne le perde pas.
 */
@HiltViewModel
class ConfirmationViewModel @Inject constructor(
    private val repository: MealRepository,
    private val nightscoutScheduler: NightscoutScheduler,
    private val nightscoutSettings: NightscoutSettings,
    private val glookoScheduler: GlookoScheduler,
    private val announcer: VoiceAnnouncer,
) : ViewModel() {

    private val _payload = MutableStateFlow<ConfirmationPayload?>(null)
    val payload: StateFlow<ConfirmationPayload?> = _payload.asStateFlow()

    private val _state = MutableStateFlow<ConfirmationUiState>(ConfirmationUiState.Idle)
    val state: StateFlow<ConfirmationUiState> = _state.asStateFlow()

    private val _nightscoutNotice = MutableStateFlow(NightscoutNotice.NONE)
    val nightscoutNotice: StateFlow<NightscoutNotice> = _nightscoutNotice.asStateFlow()

    /**
     * Ce qui a été décidé pour l'export Glooko de ce repas, `null` avant l'enregistrement.
     *
     * L'écran ne parle d'un passage à venir que si c'est vrai : une interrogation de Glooko
     * annoncée alors que l'import est coupé serait un mensonge de plus.
     */
    private val _glookoTrigger = MutableStateFlow<GlookoMealTrigger?>(null)
    val glookoTrigger: StateFlow<GlookoMealTrigger?> = _glookoTrigger.asStateFlow()

    /** Prépare un nouveau repas ; l'état d'enregistrement repart de zéro. */
    fun prepare(payload: ConfirmationPayload) {
        _payload.value = payload
        _state.value = ConfirmationUiState.Idle
        _nightscoutNotice.value = NightscoutNotice.NONE
        _glookoTrigger.value = null
    }

    fun save() {
        val payload = _payload.value ?: return
        // Double appui, rotation, retour arrière : un repas ne s'enregistre qu'une fois.
        when (_state.value) {
            ConfirmationUiState.Saving, is ConfirmationUiState.Saved -> return
            else -> Unit
        }
        viewModelScope.launch {
            _state.value = ConfirmationUiState.Saving
            _state.value = try {
                val mealId = repository.save(payload.meal)
                _nightscoutNotice.value = queueNightscout(mealId)
                // Le repas est en base : c'est l'instant qui vaut annonce, et donc celui qui
                // réveille l'export Glooko. Rien n'est bloquant ici — le passage est confié au
                // fond de tâche, quelques minutes plus tard — et une planification refusée ne
                // doit pas faire croire que le repas n'a pas été enregistré : l'écran reste
                // muet plutôt que de le contredire.
                _glookoTrigger.value = runCatching { glookoScheduler.onMealAnnounced() }.getOrNull()
                ConfirmationUiState.Saved(payload.meal.totalCarbs)
            } catch (error: Exception) {
                ConfirmationUiState.Failed(error.message ?: "cause inconnue")
            }
            // L'annonce à voix haute vient après, et à part : elle ne retarde ni
            // l'enregistrement ni l'affichage, et son échec ne change rien à ce qui est en base.
            // Elle part une seule fois, puisque `save()` refuse de repasser par ici.
            val enregistre = _state.value
            if (enregistre is ConfirmationUiState.Saved) {
                val grams = enregistre.total.grams
                viewModelScope.launch { announcer.announceMeal(grams) }
            }
        }
    }

    /**
     * Confie l'envoi au fond de tâche et dit ce qui va se passer.
     *
     * L'envoi ne bloque pas l'enregistrement : un repas est en base même si Nightscout est
     * injoignable, et c'est le journal qui garde la trace de ce qui est parti ou non.
     */
    private suspend fun queueNightscout(mealId: String): NightscoutNotice {
        val queued = nightscoutScheduler.enqueue(mealId)
        if (queued) return NightscoutNotice.QUEUED
        val config = nightscoutSettings.config.value
        return if (config.enabled && config.isComplete() && config.simulation) {
            NightscoutNotice.SIMULATED
        } else {
            NightscoutNotice.DISABLED
        }
    }

    /** Quitte l'écran : le repas confirmé ne doit pas réapparaître au retour. */
    fun clear() {
        _payload.value = null
        _state.value = ConfirmationUiState.Idle
        _nightscoutNotice.value = NightscoutNotice.NONE
        _glookoTrigger.value = null
    }
}
