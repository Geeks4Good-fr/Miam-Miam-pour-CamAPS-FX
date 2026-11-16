package com.najah.mealannouncer.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.najah.mealannouncer.core.database.repository.FavoriteMealRepository
import com.najah.mealannouncer.core.database.repository.MealRepository
import com.najah.mealannouncer.core.model.FavoriteMealLine
import com.najah.mealannouncer.core.model.MealConfirmation
import com.najah.mealannouncer.core.model.MealUnit
import com.najah.mealannouncer.core.model.NightscoutStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Une ligne d'historique : le repas, et ce qu'est devenu son envoi vers Nightscout.
 *
 * Les deux viennent de la même table mais de deux requêtes : le repas d'un côté, l'état
 * d'envoi de l'autre. Les joindre ici évite de mêler un détail de transport au modèle du
 * repas, qui sert aussi à calculer et à afficher la confirmation.
 */
data class HistoryEntry(
    val meal: MealConfirmation,
    val nightscoutStatus: NightscoutStatus,
)

/**
 * Liste des repas confirmés.
 *
 * La liste est observée depuis la base : elle se met à jour toute seule quand un repas est
 * enregistré, supprimé, ou quand son envoi Nightscout aboutit.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: MealRepository,
    private val favorites: FavoriteMealRepository,
) : ViewModel() {

    val entries: StateFlow<List<HistoryEntry>> = combine(
        repository.observeRecentMeals(LIMIT),
        repository.observeNightscoutStatuses(LIMIT),
    ) { meals, statuses ->
        meals.map { meal ->
            HistoryEntry(
                meal = meal,
                nightscoutStatus = statuses[meal.id] ?: NightscoutStatus.NOT_REQUESTED,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = emptyList(),
    )

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    /** Message passager (favori ajouté), affiché en tête du journal. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun dismissNotice() {
        _notice.value = null
    }

    /**
     * Enregistre un repas de l'historique comme favori, pour le réannoncer en un geste.
     *
     * L'historique ne recharge pas les lignes : le favori porte donc une ligne étalon de 100 g
     * valant le total, exactement comme depuis le journal des automatisations.
     */
    fun saveAsFavourite(entry: HistoryEntry) {
        val meal = entry.meal
        if (meal.totalCarbs.grams <= 0.0) return
        val label = meal.noteOrSummary().ifBlank { "Repas" }.take(80)
        viewModelScope.launch {
            favorites.create(
                label,
                listOf(
                    FavoriteMealLine(
                        name = label,
                        quantity = meal.totalCarbs.rounded(0),
                        unit = MealUnit.GRAM,
                        carbsPer100g = 100.0,
                    ),
                ),
            )
            _notice.value = "« $label » ajouté aux favoris (annonce rapide)."
        }
    }

    private companion object {
        const val LIMIT = 100
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
