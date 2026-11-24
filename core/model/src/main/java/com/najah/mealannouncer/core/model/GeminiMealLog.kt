package com.najah.mealannouncer.core.model

import java.time.Instant

/**
 * Le journal complet d'un repas mené en mode « Gemini direct » (demande du 23/11/2026).
 *
 * Premier des trois niveaux de journalisation : il garde **toute** la conversation — chaque
 * message, chaque réponse de Gemini avec ses tableaux, tels quels — et ne quitte jamais le
 * téléphone. Le Carnet Glooko et Nightscout, qui ne savent pas porter un tel texte, reçoivent
 * chacun un résumé ; [destinations] dit ce qui leur est parvenu.
 */
data class GeminiMealLog(
    val id: String,
    val startedAt: Instant,
    val updatedAt: Instant,
    /** Total retenu par Gemini au dernier échange, `null` s'il n'en a pas donné. */
    val totalCarbs: Double?,
    val minCarbs: Double?,
    val maxCarbs: Double?,
    /** Le repas en quelques mots. */
    val meal: String,
    /** Le détail court, aliment par aliment, tel qu'il part au Carnet Glooko et à Nightscout. */
    val detail: String,
    val model: String,
    val turns: List<GeminiTurn>,
    /** Ce qui est parti vers CamAPS, le Carnet Glooko et Nightscout, une ligne par envoi. */
    val destinations: List<String> = emptyList(),
) {
    /** Nombre d'échanges (un message de l'utilisateur et sa réponse). */
    val exchanges: Int get() = turns.count { it.fromUser }

    val photos: Int get() = turns.count { it.withPhoto }

    /**
     * L'heure du repas pour Nightscout : celle de l'envoi à CamAPS, lue dans [destinations]
     * (« HH:MM:SS — CamAPS : … », « confirmé » de préférence). La synchro Glooko date le repas à la
     * confirmation CamAPS ; l'anti-doublon de Nightscout compare à ±30 min. [updatedAt] ne convient
     * pas : chaque envoi après coup le repousse (doublon de 94 g du 23/11/2026, écart de 31 min).
     * À défaut de ligne CamAPS, le début de la conversation.
     */
    fun camapsMoment(zone: java.time.ZoneId): Instant {
        val lines = destinations.filter { it.contains(" — CamAPS :") }
        val line = lines.firstOrNull { it.contains("confirmé") } ?: lines.firstOrNull() ?: return startedAt
        val time = runCatching { java.time.LocalTime.parse(line.substringBefore(" — ").trim()) }
            .getOrNull() ?: return startedAt
        val start = startedAt.atZone(zone)
        var moment = start.with(time)
        // Repas commencé avant minuit et confirmé après.
        if (moment.isBefore(start.minusMinutes(1))) moment = moment.plusDays(1)
        return moment.toInstant()
    }
}

/** Un message de la conversation : de l'utilisateur (texte, photo) ou de Gemini (réponse complète). */
data class GeminiTurn(
    val fromUser: Boolean,
    /** Le texte exact : la phrase dite, ou la réponse de Gemini avec son Markdown et ses tableaux. */
    val text: String,
    /** Vrai quand une photo accompagnait ce message (la photo elle-même n'est pas gardée). */
    val withPhoto: Boolean = false,
    /** Total lu dans cette réponse de Gemini. */
    val totalCarbs: Double? = null,
)
