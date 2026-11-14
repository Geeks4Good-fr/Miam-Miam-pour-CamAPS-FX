package com.najah.mealannouncer.feature.assistant

/**
 * Transcription **sur le téléphone** (docs/VOIX-LOCALE.md).
 *
 * L'implémentation (sherpa-onnx, modèle français Kroko) vit dans le module `app`, seul à
 * embarquer la bibliothèque native. Ce module n'en connaît que ce contrat : il reste ainsi
 * compilable et testable sur la JVM sans elle.
 *
 * Règle : la transcription locale passe **en premier** quand son modèle est installé ; si elle
 * n'est pas disponible ou ne rend rien, la dictée part chez Mistral comme avant.
 */
interface LocalTranscriber {

    /** Vrai si le modèle local est installé sur le téléphone. */
    val available: Boolean

    /**
     * Transcrit [wav] (PCM 16 bits, mono, 16 kHz, en-tête WAV de 44 octets compris).
     * Rend `null` si le modèle manque, en cas d'échec, ou si rien n'a été reconnu.
     * Ne lève jamais.
     */
    suspend fun transcribe(wav: ByteArray): String?
}

/**
 * Seconde vérification du mot-clé « miam-miam » (docs/MIAM-MIAM.md § 3).
 *
 * Vosk, réduit à une poignée de mots pour veiller sans épuiser la batterie, prend volontiers une
 * syllabe de la télévision pour « miam ». Avant de réveiller l'appli, le détecteur fait réécouter
 * les dernières secondes par un vrai modèle de dictée : s'il n'y entend pas « miam », c'était
 * une fausse alerte.
 */
interface WakeWordVerifier {

    /** Vrai si la vérification est possible (modèle local installé). */
    val available: Boolean

    /**
     * Vrai si « miam » est bien entendu dans [pcm16k] (PCM 16 bits mono 16 kHz, sans en-tête).
     * Sans modèle, rend `true` : on garde alors le comportement d'avant plutôt que de rendre le
     * mot-clé sourd.
     */
    suspend fun confirms(pcm16k: ByteArray): Boolean
}
