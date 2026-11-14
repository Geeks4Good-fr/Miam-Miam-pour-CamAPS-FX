package com.najah.mealannouncer.core.datastore

import com.najah.mealannouncer.core.datastore.di.DataStoreModule
import kotlinx.coroutines.flow.StateFlow

/**
 * Ce que le reste de l'application a le droit de savoir des réglages Nightscout.
 *
 * L'interface est séparée de [NightscoutConfigStore] pour que le client Nightscout et ses
 * tests n'aient pas besoin d'un `Context` Android : un faux réglage suffit.
 */
interface NightscoutSettings {

    /** Réglages courants, réévalués à chaque modification. */
    val config: StateFlow<NightscoutConfig>

    /**
     * Secret Nightscout en clair, ou `null` s'il n'y en a pas.
     *
     * Réservé à l'envoi : à ne jamais journaliser ni recopier ailleurs. Voir [DataStoreModule].
     */
    fun readSecret(): String?
}
