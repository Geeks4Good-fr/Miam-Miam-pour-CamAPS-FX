package com.najah.mealannouncer.data.homeassistant

import org.json.JSONObject

/**
 * Un faux Home Assistant qui joue le protocole WebSocket message par message.
 *
 * [onRequest] décide de la réponse à chaque message reçu de l'appli ; par défaut, l'authentification
 * réussit et tout appel réussit avec un résultat vide. Les messages envoyés par l'appli sont gardés
 * dans [sent] pour être vérifiés.
 */
class FakeHomeAssistant(
    private val greet: Boolean = true,
    var onRequest: FakeHomeAssistant.(JSONObject) -> Unit = { defaultReply(it) },
) : WebSocketTransport {

    val sent = mutableListOf<JSONObject>()
    var openedUrl: String? = null
        private set
    var closedByApp = false
        private set
    private var events: WebSocketEvents? = null
    private var open = true

    /** Nombre de connexions ouvertes : une reconnexion en ajoute une. */
    var opens = 0
        private set

    override fun open(url: String, events: WebSocketEvents): WebSocketConnection {
        openedUrl = url
        opens++
        open = true
        this.events = events
        events.onOpen()
        if (greet) say(JSONObject().put("type", "auth_required").put("ha_version", "2026.10.4"))
        return object : WebSocketConnection {
            override fun send(text: String): Boolean {
                if (!open) return false
                val message = JSONObject(text)
                sent += message
                onRequest(message)
                return true
            }

            override fun close() {
                closedByApp = true
                drop()
            }
        }
    }

    /** Message de Home Assistant vers l'appli. */
    fun say(message: JSONObject) {
        events?.onMessage(message.toString())
    }

    /** La connexion tombe (Wi-Fi, redémarrage de HA). */
    fun drop(error: Throwable? = null) {
        if (!open) return
        open = false
        events?.onClosed(error)
    }

    fun ok(id: Int, result: JSONObject? = null) {
        say(
            JSONObject().put("id", id).put("type", "result").put("success", true)
                .put("result", result ?: JSONObject.NULL),
        )
    }

    fun refuse(id: Int, code: String, message: String) {
        say(
            JSONObject().put("id", id).put("type", "result").put("success", false)
                .put("error", JSONObject().put("code", code).put("message", message)),
        )
    }

    fun event(id: Int, event: JSONObject) {
        say(JSONObject().put("id", id).put("type", "event").put("event", event))
    }

    fun defaultReply(message: JSONObject) {
        when (message.optString("type")) {
            "auth" -> say(JSONObject().put("type", "auth_ok"))
            else -> ok(message.getInt("id"))
        }
    }

    /** Derniers messages de l'appli d'un type donné. */
    fun sentOfType(type: String): List<JSONObject> = sent.filter { it.optString("type") == type }

    companion object {
        /** Événement `subscribe_entities` : valeur présente à l'abonnement. */
        fun initial(value: String): JSONObject = JSONObject().put(
            "a",
            JSONObject().put(HomeExchange.DICTATION_ENTITY, JSONObject().put("s", value)),
        )

        /** Événement `subscribe_entities` : nouvelle valeur du champ de dictée. */
        fun change(value: String): JSONObject = JSONObject().put(
            "c",
            JSONObject().put(
                HomeExchange.DICTATION_ENTITY,
                JSONObject().put("+", JSONObject().put("s", value)),
            ),
        )
    }
}
