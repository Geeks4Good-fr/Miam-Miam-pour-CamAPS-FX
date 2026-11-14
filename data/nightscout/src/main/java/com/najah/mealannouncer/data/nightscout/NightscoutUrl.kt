package com.najah.mealannouncer.data.nightscout

import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Adresse d'un serveur Nightscout, ramenée à sa racine, et les chemins des deux API.
 *
 * Les gens collent ce qu'ils ont sous la main : l'URL de l'API avec l'identifiant devant
 * (`https://user@hote/api/v1/`), une adresse sans schéma, ou avec une barre finale. Tout
 * cela désigne le même serveur ; c'est cette racine que l'on enregistre.
 *
 * Deux API cohabitent, et elles ne savent pas la même chose :
 * - la **v3** fait un « créer ou mettre à jour » par `identifier` et sait écrire le profil,
 *   mais nombre de serveurs la refusent aux clés d'API (401) ;
 * - la **v1** accepte la clé d'API, mais ne sait qu'**ajouter** un document, et n'expose
 *   le profil qu'en lecture.
 *
 * Les chemins v1 ci-dessous sont ceux que le serveur d'essai annonce lui-même dans son
 * `swagger.json`, recopiés sans raccourci.
 */
object NightscoutUrl {

    const val STATUS_PATH = "/api/v1/status.json"

    /** Écriture d'un traitement (temps réel) : la v1 n'accepte pas de suffixe `.json` ici. */
    const val TREATMENTS_PATH = "/api/v1/treatments"

    /** Racine de l'API v3, celle qui sait faire un « créer ou mettre à jour » par identifiant. */
    const val V3_PATH = "/api/v3"

    /** Racine de l'API v1, celle que la clé d'API ouvre partout. */
    const val V1_PATH = "/api/v1"

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")

    /** Suffixes d'API que l'on retire : le client les rajoute lui-même. */
    private val API_SUFFIXES = listOf("/api/v1", "/api/v2", "/api")

    private val ISO_UTC: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /**
     * Racine du serveur (`https://hote`), ou `null` si [raw] ne peut pas en désigner une.
     *
     * L'identifiant éventuellement présent devant l'hôte est perdu : il ne doit pas finir
     * dans une adresse enregistrée, encore moins dans un journal.
     */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (SCHEME.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
        val uri = try {
            URI(withScheme)
        } catch (error: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$port${cleanPath(uri.path.orEmpty())}"
    }

    fun statusUrl(baseUrl: String): String = baseUrl.trimEnd('/') + STATUS_PATH

    fun treatmentsUrl(baseUrl: String): String = baseUrl.trimEnd('/') + TREATMENTS_PATH

    /** Collection v3 (`…/api/v3/treatments`), sans paramètre. */
    fun documentUrl(baseUrl: String, document: String): String =
        baseUrl.trimEnd('/') + V3_PATH + "/" + document.trim('/')

    /** Un document v3 précis, désigné par son `identifier`. */
    fun documentItemUrl(baseUrl: String, document: String, identifier: String): String =
        documentUrl(baseUrl, document) + "/" + identifier

    /** Collection v3 restreinte à une plage, bornes ISO 8601 en UTC. */
    fun rangeUrl(baseUrl: String, document: String, fromIso: String, toIso: String): String =
        documentUrl(baseUrl, document) + "?from=" + encode(fromIso) + "&to=" + encode(toIso)

    /**
     * La même collection en v1, pour une plage de temps.
     *
     * La v1 ne prend pas de bornes `from`/`to` : il faut lui parler le langage de Mongo,
     * `find[champ][$gte]`, où `date` (millisecondes) sert aux glycémies et `created_at`
     * (ISO) aux traitements.
     */
    fun v1RangeUrl(
        baseUrl: String,
        document: NightscoutDocument,
        fromMs: Long,
        toMs: Long,
        count: Int,
    ): String {
        val dateField = if (document == NightscoutDocument.ENTRIES) "date" else "created_at"
        val from = if (document == NightscoutDocument.ENTRIES) {
            fromMs.toString()
        } else {
            ISO_UTC.format(Instant.ofEpochMilli(fromMs))
        }
        val to = if (document == NightscoutDocument.ENTRIES) {
            toMs.toString()
        } else {
            ISO_UTC.format(Instant.ofEpochMilli(toMs))
        }
        return buildString {
            append(v1CollectionUrl(baseUrl, document))
            append("?find")
            append(encode("[$dateField]"))
            append(encode("[\$gte]"))
            append("=")
            append(encode(from))
            append("&find")
            append(encode("[$dateField]"))
            append(encode("[\$lte]"))
            append("=")
            append(encode(to))
            append("&count=")
            append(count)
        }
    }

    /** Collection v1 d'écriture (`…/api/v1/treatments`), sans paramètre. */
    fun v1CollectionUrl(baseUrl: String, document: NightscoutDocument): String =
        baseUrl.trimEnd('/') + V1_PATH + "/" + when (document) {
            NightscoutDocument.ENTRIES -> "entries"
            NightscoutDocument.TREATMENTS -> "treatments"
            NightscoutDocument.PROFILE -> "profile"
        }

    /** Un document v1 précis, désigné par l'`_id` que le serveur lui a donné. */
    fun v1ItemUrl(baseUrl: String, document: NightscoutDocument, id: String): String =
        v1CollectionUrl(baseUrl, document) + "/" + encode(id)

    /** Sert à savoir si ce serveur répond en v3 ou seulement en v1 : une lecture d'un document. */
    fun v3ProbeUrl(baseUrl: String): String =
        documentUrl(baseUrl, NightscoutDocument.ENTRIES.path) + "?limit=1"

    /** Échange d'un token d'accès contre un JWT : `…/api/v2/authorization/request/<token>`. */
    fun tokenRequestUrl(baseUrl: String, token: String): String =
        baseUrl.trimEnd('/') + "/api/v2/authorization/request/" + encode(token)

    /** Échappe ce que l'on ne veut pas voir traîner tel quel dans une URL. */
    private fun encode(value: String): String = value
        .replace("%", "%25")
        .replace("[", "%5B")
        .replace("]", "%5D")
        .replace("$", "%24")
        .replace(":", "%3A")
        .replace("+", "%2B")
        .replace(" ", "%20")

    /** Chemin de déploiement éventuel (`/nightscout`), sans suffixe d'API ni barre finale. */
    private fun cleanPath(path: String): String {
        var cleaned = path.trimEnd('/')
        var stripped = true
        while (stripped) {
            stripped = false
            val suffix = API_SUFFIXES.firstOrNull { cleaned.lowercase().endsWith(it) }
            if (suffix != null) {
                cleaned = cleaned.dropLast(suffix.length).trimEnd('/')
                stripped = true
            }
        }
        return if (cleaned.isEmpty() || cleaned == "/") "" else "/" + cleaned.trim('/')
    }
}
