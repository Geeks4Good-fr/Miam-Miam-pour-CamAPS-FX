package com.najah.mealannouncer.feature.voice

import com.najah.mealannouncer.data.voxtral.VoxtralVoice

/** Une voix telle que l'écran doit la montrer : un identifiant stable et un prénom. */
data class VoiceOption(val id: String, val label: String)

/**
 * Le catalogue des voix Voxtral, exposé par la couche qui connaît l'API.
 *
 * L'écran de réglages dépend ainsi de `feature:voice` seulement, et non de `data:voxtral` :
 * si Mistral renomme un jour ses voix, c'est ce fichier qui bouge.
 */
object VoiceCatalog {

    val options: List<VoiceOption> =
        VoxtralVoice.entries.map { VoiceOption(id = it.id, label = it.label) }

    /** Libellé d'une voix par son identifiant, ou `null` s'il est inconnu. */
    fun labelOf(id: String): String? = options.firstOrNull { it.id == id }?.label
}
