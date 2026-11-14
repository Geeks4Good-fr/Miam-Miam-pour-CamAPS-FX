package com.najah.mealannouncer.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.najah.mealannouncer.core.database.repository.MealRepository
import com.najah.mealannouncer.core.model.MealConfirmation
import com.najah.mealannouncer.core.model.NightscoutStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    private companion object {
        const val LIMIT = 100
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
