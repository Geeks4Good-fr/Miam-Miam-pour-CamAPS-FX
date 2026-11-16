package com.najah.mealannouncer.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.najah.mealannouncer.core.database.repository.FavoriteMealRepository
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
) : ViewModel() {

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
