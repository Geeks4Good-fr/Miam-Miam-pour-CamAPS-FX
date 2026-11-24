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

    /**
     * Mode « Gemini direct » (23/11/2026) : les photos et les phrases partent telles quelles à
     * Gemini, comme sur gemini.google.com, et l'appli reprend son total. Activé par défaut ;
     * désactivé, l'ancienne chaîne (lignes, Open Food Facts, relecture) reprend la main.
     */
    val geminiDirect: Boolean = true,

    /**
     * L'utilisateur se sert de la fonction **Liberty** de CamAPS FX (décision de l'équipe médicale
     * du 24/11/2026). Liberty active : l'automate n'annonce **plus jamais** les glucides dans
     * « Ajouter un repas » de CamAPS — ni saisie automatique, ni ouverture de l'écran pour une saisie
     * à la main. Tout le reste est conservé : journal, Carnet Glooko, Nightscout.
     */
    val liberty: Boolean = false,

    /**
     * La question « Utilisez-vous Liberty ? » a reçu une réponse (assistant d'installation, réglage
     * ou restauration). Tant qu'elle n'en a pas, l'assistant d'installation se rouvre au lancement.
     */
    val libertyAnswered: Boolean = false,
) {
    /** L'automate peut-il toucher à « Ajouter un repas » de CamAPS ? Jamais sous Liberty. */
    fun announcesToCamaps(): Boolean = !liberty

    /** L'insertion automatique dans CamAPS est-elle réellement en service ? */
    fun camapsAutoActive(): Boolean = camapsAutoInsert && !liberty

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
