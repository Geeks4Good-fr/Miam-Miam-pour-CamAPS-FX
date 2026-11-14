package com.najah.mealannouncer.data.homeassistant

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Ce que le transport signale au protocole. Les appels arrivent sur un fil quelconque. */
interface WebSocketEvents {
    fun onOpen()
    fun onMessage(text: String)

    /** La connexion est finie (fermée proprement ou tombée) ; [error] dit pourquoi si elle est tombée. */
    fun onClosed(error: Throwable?)
}

/** Une connexion ouverte. */
interface WebSocketConnection {
    /** Faux si le message n'a pas pu partir (connexion déjà fermée). */
    fun send(text: String): Boolean
    fun close()
}

/**
 * Unique porte de sortie réseau du module : un test en fournit un faux, qui joue Home Assistant
 * message par message, sans réseau et sans Android.
 */
fun interface WebSocketTransport {
    fun open(url: String, events: WebSocketEvents): WebSocketConnection
}

/** Transport réel, sur OkHttp. */
@Singleton
class OkHttpWebSocketTransport @Inject constructor() : WebSocketTransport {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            // Une WebSocket reste silencieuse longtemps entre deux repas : pas de délai de lecture,
            // mais un ping régulier pour savoir vite qu'elle est tombée (Wi-Fi, Tailscale).
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
            .build()
    }

    override fun open(url: String, events: WebSocketEvents): WebSocketConnection {
        val socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = events.onOpen()

                override fun onMessage(webSocket: WebSocket, text: String) = events.onMessage(text)

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL_CLOSURE, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                    events.onClosed(null)

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    events.onClosed(t)
            },
        )
        return object : WebSocketConnection {
            override fun send(text: String): Boolean = socket.send(text)
            override fun close() {
                socket.close(NORMAL_CLOSURE, null)
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_S = 10L
        const val PING_INTERVAL_S = 20L
        const val NORMAL_CLOSURE = 1000
    }
}
