package com.najah.mealannouncer.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.najah.mealannouncer.core.database.repository.FavoriteMealRepository
import com.najah.mealannouncer.core.database.repository.GeminiMealLogRepository
import com.najah.mealannouncer.core.model.GeminiMealLog
import com.najah.mealannouncer.core.model.Carbs
import com.najah.mealannouncer.data.nightscout.NightscoutRepository
import com.najah.mealannouncer.data.nightscout.NightscoutSendOutcome
import com.najah.mealannouncer.integration.glookoexport.GlookoCarnet
import com.najah.mealannouncer.integration.glookoexport.GlookoCarnetMoment
import com.najah.mealannouncer.integration.glookoexport.GlookoCarnetResult
import java.time.LocalTime
import java.time.ZoneId
import com.najah.mealannouncer.core.database.repository.JournalRepository
import com.najah.mealannouncer.core.model.AutomationEntry
import com.najah.mealannouncer.core.model.AutomationSummary
import com.najah.mealannouncer.core.model.FavoriteMealLine
import com.najah.mealannouncer.core.model.MealUnit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Le journal : ce que l'automatisation a réussi à faire de chaque annonce.
 *
 * Trois issues sont distinguées à l'écran, dans cet ordre de lecture : menée **jusqu'au bout**,
 * arrêtée **à CamAPS**, et les cas sans suite (annulé, échec). C'est la demande du 14/11/2026
 * (point 3) : sans cette distinction, un repas seulement déposé dans CamAPS se confondrait avec
 * un repas complet.
 */
@HiltViewModel
class JournalViewModel @Inject constructor(
    private val journal: JournalRepository,
    private val favorites: FavoriteMealRepository,
    private val geminiLog: GeminiMealLogRepository,
    private val glookoCarnet: GlookoCarnet,
    private val nightscout: NightscoutRepository,
) : ViewModel() {

    /** Les repas menés avec Gemini, avec toute leur conversation (niveau 1 de la journalisation). */
    val geminiMeals: StateFlow<List<GeminiMealLog>> = geminiLog.observeRecent(LIMIT).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = emptyList(),
    )

    fun deleteGeminiMeal(id: String) {
        viewModelScope.launch { geminiLog.delete(id) }
    }

    /** Vrai pendant qu'un envoi du journal Gemini (Carnet ou Nightscout) est en cours. */
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    /**
     * Note après coup un repas Gemini dans le Carnet Glooko : le détail lu dans la réponse de Gemini
     * et son total. Sert quand l'enchaînement automatique s'est arrêté (essai du 23/11/2026 : CamAPS
     * complété à la main, Carnet jamais écrit). CamAPS n'est jamais touché ici.
     */
    fun noteInGlooko(meal: GeminiMealLog) {
        val total = meal.totalCarbs ?: return
        if (_sending.value) return
        viewModelScope.launch {
            _sending.value = true
            try {
                val carbs = Carbs.of(total).toCamapsGrams()
                val description = "Gemini : " + meal.detail.ifBlank { meal.meal }
                val moment = GlookoCarnetMoment.forHour(meal.startedAt.atZone(ZoneId.systemDefault()).hour)
                val line = when (val result = glookoCarnet.addEntry(description, carbs, moment)) {
                    is GlookoCarnetResult.Saved -> "Carnet Glooko : noté après coup ($carbs g) — $description"
                    is GlookoCarnetResult.Failed -> "Carnet Glooko : échec — ${result.message}"
                }
                geminiLog.addDestination(meal.id, stamp(line))
                _notice.value = line
            } finally {
                _sending.value = false
            }
        }
    }

    /**
     * Envoie après coup un repas Gemini à Nightscout : glucides et détail, avec le même anti-doublon
     * que l'envoi automatique (une entrée Glooko-sync correspondante → description seule).
     */
    fun sendToNightscout(meal: GeminiMealLog) {
        val total = meal.totalCarbs ?: return
        if (_sending.value) return
        viewModelScope.launch {
            _sending.value = true
            try {
                val carbs = Carbs.of(total).toCamapsGrams()
                val note = "Miam-Miam (Gemini) : " + meal.detail.ifBlank { meal.meal }
                // L'heure du repas = celle de CamAPS (comme la synchro Glooko), jamais celle de l'envoi.
                val sent = nightscout.sendMealWithCarbs(carbs, note, meal.camapsMoment(ZoneId.systemDefault()))
                val label = when (val outcome = sent.outcome) {
                    NightscoutSendOutcome.Sent -> if (sent.carbsCounted) {
                        "$carbs g envoyés avec la description"
                    } else {
                        "description envoyée seule (glucides déjà comptés par la synchro Glooko)"
                    }
                    NightscoutSendOutcome.Simulated -> "simulation, rien envoyé"
                    NightscoutSendOutcome.Disabled -> "Nightscout non réglé, rien envoyé"
                    is NightscoutSendOutcome.Failed -> "échec (${outcome.kind})"
                    else -> outcome.toString()
                }
                val line = "Nightscout : $label (après coup)"
                geminiLog.addDestination(meal.id, stamp(line))
                _notice.value = line
            } finally {
                _sending.value = false
            }
        }
    }

    private fun stamp(line: String) = "${LocalTime.now().withNano(0)} — $line"

    val entries: StateFlow<List<AutomationEntry>> = journal.observeRecent(LIMIT).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = emptyList(),
    )

    val summary: StateFlow<AutomationSummary> = journal.observeSummary().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = AutomationSummary(),
    )

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun delete(id: String) {
        viewModelScope.launch { journal.delete(id) }
    }

    fun clear() {
        viewModelScope.launch {
            journal.clear()
            _notice.value = "Journal vidé."
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    /**
     * Enregistre une annonce du journal comme repas favori (point 4 : les favoris servent aussi
     * depuis le journal).
     *
     * Le journal ne garde que la description et le total : le favori créé porte donc **une ligne
     * étalon** de 100 g valant le total. Rechargé, il redonne exactement les mêmes glucides, sans
     * prétendre connaître le détail d'origine — et il reste modifiable au gramme près.
     */
    fun saveAsFavourite(entry: AutomationEntry) {
        if (entry.carbs <= 0 || entry.description.isBlank()) return
        val line = FavoriteMealLine(
            name = entry.description.take(80),
            brand = null,
            quantity = entry.carbs.toDouble(),
            unit = MealUnit.GRAM,
            carbsPer100g = 100.0,
        )
        viewModelScope.launch {
            favorites.create(entry.description.take(80), listOf(line))
            _notice.value = "« ${entry.description.take(60)} » ajouté aux favoris (annonce rapide)."
        }
    }

    private companion object {
        const val LIMIT = 200
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
