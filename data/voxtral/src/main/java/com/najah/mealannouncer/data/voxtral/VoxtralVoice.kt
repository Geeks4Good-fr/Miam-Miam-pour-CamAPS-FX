package com.najah.mealannouncer.data.voxtral

/**
 * Les voix françaises de Voxtral (relevées le 24/10/2026, `docs/VOXTRAL.md` § 2.1).
 *
 * L'identifiant est ce que l'API attend dans `voice_id`. Marie est la voix par défaut tant
 * que les quatre n'ont pas été écoutées et qu'un choix n'a pas été fait.
 */
enum class VoxtralVoice(val id: String, val label: String) {
    MARIE("voice_592", "Marie"),
    MANON("voice_3137", "Manon"),
    LEA("voice_589", "Léa"),
    CAMILLE("voice_3141", "Camille"),
    ;

    companion object {
        val DEFAULT = MARIE

        /** Retrouve une voix par son identifiant d'API, ou la voix par défaut si inconnu. */
        fun fromId(id: String?): VoxtralVoice =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Format audio demandé à Voxtral. `mp3` est le défaut : lisible par `MediaPlayer` sans
 * dépendance, et assez compact pour le cache de phrases.
 */
enum class VoxtralAudioFormat(val wire: String) {
    MP3("mp3"),
    WAV("wav"),
    OPUS("opus"),
    FLAC("flac"),
    PCM("pcm"),
    ;

    companion object {
        val DEFAULT = MP3
    }
}
