package com.najah.mealannouncer.data.nightscout

import com.najah.mealannouncer.core.datastore.CredentialType
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Ce qu'il faut pour parler à un serveur Nightscout.
 *
 * [secret] est en clair : cette classe ne doit jamais être journalisée, sérialisée ni
 * passée à un écran.
 */
data class NightscoutCredentials(
    val baseUrl: String,
    val credentialType: CredentialType,
    val secret: String,
)

/** Renseignements renvoyés par `status.json`, utiles pour rassurer après un test. */
data class NightscoutServerInfo(
    val name: String,
    val version: String,
    val apiEnabled: Boolean,
    val careportalEnabled: Boolean,
    val units: String,
)

/** Comment un serveur donné accepte d'être lu et écrit. */
internal enum class NightscoutApiStyle {
    /** v3 : « créer ou mettre à jour » par identifiant, profil écrivable. */
    V3,

    /** v1 : ajout seul, profil en lecture seule, mais la clé d'API y suffit toujours. */
    V1,
}

interface NightscoutApi {

    /** Vérifie l'adresse et l'identifiant. Lève [NightscoutException] sinon. */
    fun status(credentials: NightscoutCredentials): NightscoutServerInfo

    /** Écrit un traitement par l'API v1. Lève [NightscoutException] si Nightscout refuse. */
    fun postTreatment(credentials: NightscoutCredentials, payload: String)

    /**
     * Écrit un document : mise à jour si son `identifier` est connu, création sinon.
     *
     * C'est cette propriété qui rend un import rejouable sans doublon — elle est native en
     * v3, et assurée en v1 par la lecture préalable que fait l'import (qui écarte les
     * identifiants déjà présents avant d'écrire).
     *
     * @return le code HTTP, 201 à la création et 200 à la mise à jour.
     * @throws NightscoutException avec [NightscoutErrorKind.PROFILE_UNSUPPORTED] si le
     * serveur n'accepte le profil qu'en v3 et que seule la v1 est ouverte.
     */
    fun upsert(
        credentials: NightscoutCredentials,
        document: NightscoutDocument,
        payload: String,
    ): Int

    /**
     * Supprime un document, désigné par son `identifier` (v3) ou par l'`_id` du serveur
     * (v1, [itemId]).
     *
     * Un 404 n'est pas une erreur : le document avait déjà disparu, et l'appelant n'a rien
     * d'autre à faire. Il est donc rendu au lieu d'être levé.
     */
    fun delete(
        credentials: NightscoutCredentials,
        document: NightscoutDocument,
        identifier: String,
        itemId: String? = null,
    ): Int

    /** Glycémies déjà présentes entre deux instants, bornes incluses. */
    fun entries(
        credentials: NightscoutCredentials,
        fromMs: Long,
        toMs: Long,
    ): List<NightscoutEntryRef>

    /** Traitements déjà présents entre deux instants, bornes incluses. */
    fun treatments(
        credentials: NightscoutCredentials,
        fromMs: Long,
        toMs: Long,
    ): List<NightscoutTreatmentRef>
}

/**
 * Client HTTP de Nightscout.
 *
 * L'authentification tient en un en-tête `api-secret`. Pour une clé `API_SECRET`,
 * Nightscout attend le **SHA-1 hexadécimal** du secret ; envoyer le secret en clair
 * donne un 401 (vérifié sur le serveur d'essai le 24/10/2026). Un token d'accès, lui,
 * part tel quel.
 *
 * Deux API coexistent, et toutes deux lisent le même en-tête :
 * - la **v3** sait mettre à jour par `identifier` et écrire le profil, mais beaucoup de
 *   serveurs la réservent aux tokens et répondent 401 à une clé d'API — c'est le cas du
 *   serveur de l'utilisateur, mesuré le 24/10/2026 sur `entries`, `treatments` et `profile` ;
 * - la **v1** répond toujours à la clé d'API, mais n'ajoute que des documents et ne sait
 *   pas écrire de profil.
 *
 * Le style est donc demandé au serveur une fois (une lecture d'un document v3), puis
 * mémorisé pour ce couple adresse/identifiant.
 */
class HttpNightscoutApi @Inject constructor(
    private val transport: HttpTransport,
) : NightscoutApi {

    /**
     * Style imposé au lieu de celui que la sonde trouverait.
     *
     * Les tests s'en servent pour éprouver chaque API séparément, sans rejouer la
     * négociation à chaque fois.
     */
    internal constructor(transport: HttpTransport, style: NightscoutApiStyle) : this(transport) {
        imposedStyle = style
    }

    private var imposedStyle: NightscoutApiStyle? = null


    /**
     * Ce que ce serveur a répondu la première fois. La clé porte l'adresse et une empreinte
     * de l'en-tête, pas le secret lui-même : changer de secret invalide la mémoire.
     */
    private data class StyleKey(val base: String, val headerHash: Int)

    @Volatile
    private var styleCache: Pair<StyleKey, NightscoutApiStyle>? = null

    /** JWT obtenu en échange d'un token d'accès (valable ~8 h sur Nightscout), gardé en cache. */
    private data class CachedJwt(val base: String, val token: String, val value: String, val notAfterMs: Long)

    private var jwtCache: CachedJwt? = null

    override fun status(credentials: NightscoutCredentials): NightscoutServerInfo {
        val response = call(
            credentials = credentials,
            method = "GET",
            url = NightscoutUrl.statusUrl(baseFor(credentials)),
            body = null,
        )
        return parseStatus(response.body)
    }

    override fun postTreatment(credentials: NightscoutCredentials, payload: String) {
        call(
            credentials = credentials,
            method = "POST",
            url = NightscoutUrl.treatmentsUrl(baseFor(credentials)),
            body = payload,
        )
    }

    override fun upsert(
        credentials: NightscoutCredentials,
        document: NightscoutDocument,
        payload: String,
    ): Int {
        val base = baseFor(credentials)
        return when (styleFor(credentials, base)) {
            NightscoutApiStyle.V3 -> call(
                credentials = credentials,
                method = "POST",
                url = NightscoutUrl.documentUrl(base, document.path),
                body = payload,
            ).code

            NightscoutApiStyle.V1 -> {
                if (document == NightscoutDocument.PROFILE) throw profileReadOnly()
                call(
                    credentials = credentials,
                    method = "POST",
                    url = NightscoutUrl.v1CollectionUrl(base, document),
                    body = payload,
                ).code
            }
        }
    }

    override fun delete(
        credentials: NightscoutCredentials,
        document: NightscoutDocument,
        identifier: String,
        itemId: String?,
    ): Int {
        val base = baseFor(credentials)
        val url = when (styleFor(credentials, base)) {
            NightscoutApiStyle.V3 ->
                NightscoutUrl.documentItemUrl(base, document.path, identifier)

            NightscoutApiStyle.V1 -> {
                // La v1 ne connaît que l'`_id` qu'elle a fabriqué : sans lui, le document
                // n'a jamais existé côté serveur et il n'y a rien à retirer.
                val id = itemId?.takeIf { it.isNotBlank() } ?: return NOTHING_DELETED
                NightscoutUrl.v1ItemUrl(base, document, id)
            }
        }
        val response = guardTransport {
            transport.execute(request(credentials, "DELETE", url, body = null))
        }
        if (response.code == 404) return response.code
        ensureSuccess(response)
        return response.code
    }

    override fun entries(
        credentials: NightscoutCredentials,
        fromMs: Long,
        toMs: Long,
    ): List<NightscoutEntryRef> =
        readDocuments(credentials, NightscoutDocument.ENTRIES, fromMs, toMs).mapNotNull { item ->
            val dateMs = item.optLong("date", 0L)
            val resolved = if (dateMs > 0) {
                dateMs
            } else {
                isoToEpochMs(item.optString("dateString")) ?: return@mapNotNull null
            }
            NightscoutEntryRef(
                dateMs = resolved,
                device = item.optString("device").takeIf { it.isNotBlank() },
                identifier = item.optString("identifier").takeIf { it.isNotBlank() },
                id = item.optString("_id").takeIf { it.isNotBlank() },
            )
        }

    override fun treatments(
        credentials: NightscoutCredentials,
        fromMs: Long,
        toMs: Long,
    ): List<NightscoutTreatmentRef> =
        readDocuments(credentials, NightscoutDocument.TREATMENTS, fromMs, toMs).mapNotNull { item ->
            val created = isoToEpochMs(item.optString("created_at"))
                ?: item.optLong("date", 0L).takeIf { it > 0 }
                ?: return@mapNotNull null
            NightscoutTreatmentRef(
                createdAtMs = created,
                eventType = item.optString("eventType").takeIf { it.isNotBlank() },
                enteredBy = item.optString("enteredBy").takeIf { it.isNotBlank() },
                identifier = item.optString("identifier").takeIf { it.isNotBlank() },
                id = item.optString("_id").takeIf { it.isNotBlank() },
            )
        }

    /** Tous les documents d'une collection sur une plage, quel que soit le style du serveur. */
    private fun readDocuments(
        credentials: NightscoutCredentials,
        document: NightscoutDocument,
        fromMs: Long,
        toMs: Long,
    ): List<JSONObject> {
        val base = baseFor(credentials)
        return when (styleFor(credentials, base)) {
            NightscoutApiStyle.V3 -> readOnce(
                credentials,
                NightscoutUrl.rangeUrl(
                    baseUrl = base,
                    document = document.path,
                    fromIso = ISO_MILLIS.format(Instant.ofEpochMilli(fromMs)),
                    toIso = ISO_MILLIS.format(Instant.ofEpochMilli(toMs)),
                ),
            ).items()

            NightscoutApiStyle.V1 -> buildList {
                var chunkStart = fromMs
                while (chunkStart <= toMs) {
                    val chunkEnd = minOf(chunkStart + V1_CHUNK_MS, toMs)
                    addAll(
                        readV1Chunk(
                            credentials = credentials,
                            base = base,
                            document = document,
                            fromMs = chunkStart,
                            toMs = chunkEnd,
                            depth = 0,
                        ),
                    )
                    chunkStart = chunkEnd + 1
                }
            }
        }
    }

    /**
     * Lit une tranche de temps en v1.
     *
     * `count` borne le nombre de documents rendus, et rien ne dit ce qu'un serveur fait
     * d'une demande plus grande que sa propre limite : une page pleine est donc traitée
     * comme une page peut-être tronquée, que l'on coupe en deux jusqu'à ce qu'elle ne le
     * soit plus. Une lecture incomplète ferait réécrire des repas déjà présents.
     */
    private fun readV1Chunk(
        credentials: NightscoutCredentials,
        base: String,
        document: NightscoutDocument,
        fromMs: Long,
        toMs: Long,
        depth: Int,
    ): List<JSONObject> {
        val page = readOnce(
            credentials,
            NightscoutUrl.v1RangeUrl(base, document, fromMs, toMs, V1_PAGE_SIZE),
        ).items()
        val saturated = page.size >= V1_PAGE_SIZE
        if (!saturated || fromMs >= toMs || depth >= V1_MAX_BISECT) return page
        val middle = fromMs + (toMs - fromMs) / 2
        return readV1Chunk(credentials, base, document, fromMs, middle, depth + 1) +
            readV1Chunk(credentials, base, document, middle + 1, toMs, depth + 1)
    }

    private fun readOnce(credentials: NightscoutCredentials, url: String): JSONArray {
        val response = guardTransport {
            transport.execute(request(credentials, "GET", url, body = null))
        }
        ensureSuccess(response)
        return resultArray(response.body)
    }

    /**
     * Le style accepté par ce serveur, demandé une seule fois.
     *
     * La v3 répond 200 à une lecture d'un document quand elle est ouverte ; 401, 403 ou
     * 404 disent qu'elle ne l'est pas (serveur ancien, ou clé d'API refusée) et la v1 prend
     * le relais. Toute autre réponse est une vraie panne, classée comme telle.
     */
    private fun styleFor(credentials: NightscoutCredentials, base: String): NightscoutApiStyle {
        imposedStyle?.let { return it }
        val key = StyleKey(base, authorizationHeader(credentials).hashCode())
        styleCache?.let { (cachedKey, cachedStyle) -> if (cachedKey == key) return cachedStyle }
        val response = guardTransport {
            transport.execute(request(credentials, "GET", NightscoutUrl.v3ProbeUrl(base), body = null))
        }
        val style = when {
            response.code in 200..299 -> NightscoutApiStyle.V3
            response.code == 401 || response.code == 403 || response.code == 404 ->
                NightscoutApiStyle.V1

            else -> {
                ensureSuccess(response)
                NightscoutApiStyle.V3
            }
        }
        styleCache = key to style
        return style
    }

    private fun baseFor(credentials: NightscoutCredentials): String {
        if (credentials.secret.isBlank()) {
            throw NightscoutException(
                NightscoutErrorKind.NOT_CONFIGURED,
                "Aucun secret Nightscout enregistré",
            )
        }
        return NightscoutUrl.normalize(credentials.baseUrl) ?: throw NightscoutException(
            NightscoutErrorKind.NOT_CONFIGURED,
            "Adresse Nightscout absente ou invalide",
        )
    }

    private fun request(
        credentials: NightscoutCredentials,
        method: String,
        url: String,
        body: String?,
    ): HttpRequest {
        // `api-secret` ouvre toujours la v1 (SHA-1 d'une clé, ou le token tel quel). La v3 de
        // certains serveurs (celui de l'essai) refuse le token dans cet en-tête et n'accepte
        // qu'un JWT en « Authorization: Bearer » : on l'ajoute quand on en a un.
        val headers = HashMap<String, String>()
        headers["api-secret"] = authorizationHeader(credentials)
        if (credentials.credentialType == CredentialType.ACCESS_TOKEN) {
            bearerFor(credentials)?.let { headers["Authorization"] = "Bearer $it" }
        }
        return HttpRequest(
            method = method,
            url = url,
            headers = headers,
            body = body,
            contentType = if (body != null) "application/json" else null,
        )
    }

    /**
     * Échange le token d'accès contre un JWT via `/api/v2/authorization/request/<token>`,
     * et le garde jusqu'à une minute avant son expiration. Renvoie null si l'échange échoue :
     * la requête part alors avec le seul `api-secret`, et le serveur retombe en v1.
     */
    private fun bearerFor(credentials: NightscoutCredentials): String? {
        val base = runCatching { baseFor(credentials) }.getOrNull() ?: return null
        val token = credentials.secret
        val now = System.currentTimeMillis()
        jwtCache?.let { cached ->
            if (cached.base == base && cached.token == token && now < cached.notAfterMs) {
                return cached.value
            }
        }
        val response = guardTransport {
            transport.execute(
                HttpRequest(
                    method = "GET",
                    url = NightscoutUrl.tokenRequestUrl(base, token),
                    headers = emptyMap(),
                    body = null,
                    contentType = null,
                ),
            )
        }
        if (response.code !in 200..299) return null
        val jwt = runCatching { JSONObject(response.body).optString("token", "") }.getOrDefault("")
        if (jwt.isBlank()) return null
        val notAfter = (jwtExpiryMs(jwt) ?: (now + 5 * 60_000L)) - 60_000L
        jwtCache = CachedJwt(base, token, jwt, notAfter)
        return jwt
    }

    /** Lit `exp` (secondes) dans la charge utile du JWT ; null si illisible. */
    private fun jwtExpiryMs(jwt: String): Long? = runCatching {
        val parts = jwt.split(".")
        if (parts.size < 2) return null
        var payload = parts[1].replace('-', '+').replace('_', '/')
        while (payload.length % 4 != 0) payload += "="
        val json = String(java.util.Base64.getDecoder().decode(payload), Charsets.UTF_8)
        JSONObject(json).optLong("exp", 0L).takeIf { it > 0 }?.times(1000L)
    }.getOrNull()

    private fun call(
        credentials: NightscoutCredentials,
        method: String,
        url: String,
        body: String?,
    ): HttpResponse {
        val response = guardTransport { transport.execute(request(credentials, method, url, body)) }
        ensureSuccess(response)
        return response
    }

    private fun ensureSuccess(response: HttpResponse) {
        when {
            response.code in 200..299 -> Unit
            response.code == 401 || response.code == 403 -> throw NightscoutException(
                NightscoutErrorKind.UNAUTHORIZED,
                "Nightscout a refusé l'identifiant (${response.code})",
            )
            response.code == 404 -> throw NightscoutException(
                NightscoutErrorKind.NOT_FOUND,
                "Adresse introuvable sur Nightscout (404)",
            )
            response.code >= 500 -> throw NightscoutException(
                NightscoutErrorKind.SERVER,
                "Nightscout a répondu ${response.code}",
            )
            else -> throw NightscoutException(
                NightscoutErrorKind.UNEXPECTED,
                "Nightscout a répondu ${response.code}",
            )
        }
    }

    private fun parseStatus(body: String): NightscoutServerInfo {
        val json = try {
            JSONObject(body)
        } catch (error: JSONException) {
            throw NightscoutException(
                NightscoutErrorKind.INVALID_RESPONSE,
                "status.json illisible",
                error,
            )
        }
        val settings = json.optJSONObject("settings")
        return NightscoutServerInfo(
            name = json.optString("name"),
            version = json.optString("version"),
            apiEnabled = json.optBoolean("apiEnabled", settings?.optBoolean("apiEnabled") ?: false),
            careportalEnabled = json.optBoolean("careportalEnabled", false),
            units = settings?.optString("units").orEmpty(),
        )
    }

    /**
     * La v3 répond `{ "status": 200, "result": [ … ] }`, la v1 répond directement un
     * tableau : les deux formes sont acceptées, une réponse vide ne fait pas échouer.
     */
    private fun resultArray(body: String): JSONArray {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return JSONArray()
        return try {
            if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                JSONObject(trimmed).optJSONArray("result") ?: JSONArray()
            }
        } catch (error: JSONException) {
            throw NightscoutException(
                NightscoutErrorKind.INVALID_RESPONSE,
                "Réponse de lecture Nightscout illisible",
                error,
            )
        }
    }

    /** Les objets d'une réponse de lecture, sans les entrées qui n'en sont pas. */
    private fun JSONArray.items(): List<JSONObject> = buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let { add(it) }
        }
    }

    private fun profileReadOnly(): NightscoutException = NightscoutException(
        NightscoutErrorKind.PROFILE_UNSUPPORTED,
        "Ce serveur n'écrit le profil qu'en v3, et refuse la v3 à une clé d'API",
    )

    private companion object {
        val ISO_MILLIS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /** Taille des tranches v1 : assez courte pour que 500 documents y tiennent. */
        const val V1_CHUNK_MS = 12L * 60 * 60 * 1000

        /** Documents demandés par page v1 : au-delà, on ne sait plus si la réponse est entière. */
        const val V1_PAGE_SIZE = 500

        /** Profondeur maximale de découpage : 12 h coupées dix fois font 42 secondes. */
        const val V1_MAX_BISECT = 10

        /** Rien à supprimer : le document n'existait pas côté serveur. */
        const val NOTHING_DELETED = 204
    }
}

/** Convertit une date ISO de Nightscout en millisecondes, ou `null` si elle est illisible. */
internal fun isoToEpochMs(value: String): Long? {
    if (value.isBlank()) return null
    return try {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    } catch (error: Exception) {
        try {
            Instant.parse(value).toEpochMilli()
        } catch (second: Exception) {
            null
        }
    }
}

/** Valeur de l'en-tête `api-secret` : SHA-1 hexadécimal pour une clé d'API, sinon le token. */
internal fun authorizationHeader(credentials: NightscoutCredentials): String =
    when (credentials.credentialType) {
        CredentialType.API_SECRET -> sha1Hex(credentials.secret)
        CredentialType.ACCESS_TOKEN -> credentials.secret
    }

/** SHA-1 hexadécimal en minuscules, la forme qu'attend Nightscout. */
internal fun sha1Hex(value: String): String =
    MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
