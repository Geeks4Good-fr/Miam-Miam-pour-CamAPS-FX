package com.najah.mealannouncer.data.mistral

import org.json.JSONObject

/**
 * Chronomètre des appels Mistral (09/11/2026 : le calcul des glucides est jugé trop long).
 *
 * Chaque appel note son modèle, sa durée et ses jetons. Le module ne dépend pas d'Android :
 * l'application branche [sink] sur le journal du téléphone (étiquette `MealAnnouncerTiming`).
 * Jamais de clé ni de texte du repas ici, seulement des durées et des compteurs.
 */
object MistralTiming {

    @Volatile
    var sink: ((String) -> Unit)? = null

    internal inline fun <T> measure(kind: String, model: String, block: () -> T): T {
        val started = System.currentTimeMillis()
        var outcome = "échec"
        try {
            val result = block()
            outcome = "ok"
            return result
        } finally {
            val elapsed = System.currentTimeMillis() - started
            runCatching { sink?.invoke("$kind $model : $elapsed ms ($outcome)") }
        }
    }

    /** Jetons consommés, lus dans le champ `usage` de la réponse ; vide s'il manque. */
    internal fun usage(body: String): String = runCatching {
        val u = JSONObject(body).optJSONObject("usage") ?: return ""
        " — jetons : ${u.optInt("prompt_tokens")} entrée, ${u.optInt("completion_tokens")} sortie"
    }.getOrDefault("")

    internal fun note(text: String) {
        runCatching { sink?.invoke(text) }
    }
}
