package com.najah.mealannouncer.feature.voice

import javax.inject.Qualifier

/**
 * Un moteur capable de dire une phrase à voix haute.
 *
 * `true` signifie que la phrase a **été jouée** (le moteur a fini de la lire), `false` qu'elle
 * ne l'a pas été : l'appelant bascule alors sur le repli. Aucune exception ne doit sortir d'un
 * moteur — une annonce qui échoue doit se taire proprement, pas faire tomber l'application.
 */
interface VoiceEngineTts {

    suspend fun speak(text: String): Boolean

    /**
     * Dernière cause d'échec, en clair et **sans aucun secret** ; `null` après une réussite.
     *
     * Elle existe pour l'écran d'essai : savoir *pourquoi* une voix n'a pas parlé évite de
     * chercher à l'aveugle (clé refusée ? pas de réseau ? pas de voix française installée ?).
     * Ce n'est pas un journal : personne ne l'écrit dans un fichier, c'est l'utilisateur qui la lit.
     */
    val lastIssue: String?
        get() = null
}

/** La voix du système Android : hors ligne, toujours là, sert de repli. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class AndroidEngine

/** La voix Voxtral (Mistral) : meilleure, mais dépend du réseau et d'un consentement. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class VoxtralEngine

/**
 * La voix locale Piper (sherpa-onnx) : calculée sur le téléphone, sans réseau ni clé.
 * Son implémentation vit dans le module `app`, seul à embarquer la bibliothèque native.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class PiperEngine
