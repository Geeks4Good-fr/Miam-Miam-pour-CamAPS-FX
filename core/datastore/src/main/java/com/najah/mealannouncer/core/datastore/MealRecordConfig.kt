package com.najah.mealannouncer.core.datastore

import kotlinx.coroutines.flow.StateFlow

/**
 * Réglages de l'enregistrement d'un repas (contrat `docs/CAMAPS-REPAS.md`).
 *
 * Deux régimes : au quotidien, le **mode rapide** (le suivi désactivé) ne fait que l'essentiel ;
 * le **suivi diabétologue**, activé à la main les quelques jours avant un rendez-vous, ajoute la
 * description détaillée pour le médecin. Tout est pensé pour la charge mentale minimale : un
 * diabétique veut manger vite, pas décrire chaque collation.
 */
data class MealRecordConfig(
    /**
     * Insertion automatique des glucides dans CamAPS par le service d'accessibilité.
     *
     * Désactivée par défaut : sans elle, CamAPS est seulement **ouvert** et la saisie reste
     * manuelle. L'activer exige en plus d'allumer le service d'accessibilité dans Android.
     */
    val camapsAutoInsert: Boolean = false,

    /**
     * Mode « Suivi diabétologue ».
     *
     * Désactivé par défaut. Une fois actif, la description du repas part dans le Carnet Glooko et
     * la note Nightscout — utile avant un rendez-vous, inutile le reste du temps.
     */
    val suiviDiabetologue: Boolean = false,

    /** Sous le suivi : écrire l'entrée du Carnet Glooko (désactivable séparément). */
    val glookoCarnet: Boolean = true,

    /** Sous le suivi : écrire la note dans Nightscout (désactivable séparément). */
    val nightscoutNote: Boolean = true,

    /** Sous le suivi : joindre la photo du plat au Carnet Glooko quand l'assistant en a une. */
    val attachPhoto: Boolean = true,
) {
    /** Le Carnet Glooko est-il écrit pour ce repas ? */
    fun writesGlookoCarnet(): Boolean = suiviDiabetologue && glookoCarnet

    /** La note Nightscout est-elle écrite pour ce repas ? */
    fun writesNightscoutNote(): Boolean = suiviDiabetologue && nightscoutNote

    /** La photo est-elle jointe au Carnet pour ce repas ? */
    fun attachesPhoto(): Boolean = writesGlookoCarnet() && attachPhoto
}

/** Réglages de l'enregistrement d'un repas, observables par l'interface. */
interface MealRecordSettings {

    val config: StateFlow<MealRecordConfig>
}
