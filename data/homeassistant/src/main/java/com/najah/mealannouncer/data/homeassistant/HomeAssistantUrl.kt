package com.najah.mealannouncer.data.homeassistant

/**
 * Adresse de la WebSocket de Home Assistant, déduite de l'adresse saisie dans les réglages.
 *
 * L'utilisateur tape ce qu'il voit dans son navigateur (`http://100.64.0.10`,
 * `https://ha.exemple.fr/`, voire `100.64.0.10:8123`) ; on en tire `ws://…/api/websocket`
 * ou `wss://…/api/websocket`. Rien d'autre que le schéma, l'hôte, le port et un éventuel
 * préfixe de chemin n'est gardé.
 */
object HomeAssistantUrl {

    private const val WEBSOCKET_PATH = "/api/websocket"

    /** Adresse WebSocket, ou `null` si [base] n'est pas une adresse HTTP(S) exploitable. */
    fun websocketUrl(base: String): String? {
        var text = base.trim()
        if (text.isEmpty()) return null
        if (!text.contains("://")) text = "http://$text"

        val scheme = text.substringBefore("://").lowercase()
        val wsScheme = when (scheme) {
            "http", "ws" -> "ws"
            "https", "wss" -> "wss"
            else -> return null
        }
        var rest = text.substringAfter("://").substringBefore('?').substringBefore('#').trimEnd('/')
        if (rest.endsWith(WEBSOCKET_PATH)) rest = rest.removeSuffix(WEBSOCKET_PATH).trimEnd('/')
        val host = rest.substringBefore('/')
        if (host.isEmpty() || host.any { it.isWhitespace() }) return null
        return "$wsScheme://$rest$WEBSOCKET_PATH"
    }
}
