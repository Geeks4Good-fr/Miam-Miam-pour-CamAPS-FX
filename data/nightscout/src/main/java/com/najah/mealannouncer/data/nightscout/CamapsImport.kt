package com.najah.mealannouncer.data.nightscout

import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject

/**
 * Import vers un **Nightscout for CamAPS FX** (le fork « Journée CamAPS », contrat P7 du
 * 29/10/2026 : `NightscoutCamAPS\projet\p7-adaptation-importateur.md`).
 *
 * L'appli **transporte** le CSV de Glooko, octet pour octet, et c'est le serveur qui l'interprète
 * (D-10 / ADR-006). Deux temps : un **aperçu** (rien n'est encore visible), puis une **confirmation**
 * qui cite l'aperçu par son identifiant et son empreinte. Rien n'est écrit dans `entries`,
 * `treatments` ni `profile`.
 */
data class CamapsImportSummary(
    val days: Int,
    val total: Int,
    val new: Int,
    val alreadyKnown: Int,
    val conflicts: Int,
    val possibleCorrections: Int,
    val ambiguous: Int,
    val unsupported: Int,
    val unmapped: Int,
    val rejectedRows: Int,
) {
    /** Une phrase pour l'écran des réglages. */
    fun text(): String = buildString {
        append("$total événement(s) sur $days jour(s) : $new nouveau(x), $alreadyKnown déjà connu(s)")
        if (conflicts > 0) append(", $conflicts conflit(s)")
        if (possibleCorrections > 0) append(", $possibleCorrections correction(s) possible(s)")
        if (ambiguous > 0) append(", $ambiguous ambigu(s)")
        if (unsupported + unmapped > 0) append(", ${unsupported + unmapped} non pris en charge")
        if (rejectedRows > 0) append(", $rejectedRows ligne(s) refusée(s)")
        append('.')
    }
}

/** Réponse de l'aperçu : à citer telle quelle pour confirmer. Expire au bout de 30 minutes. */
data class CamapsPreview(
    val previewId: String,
    val previewDigest: String,
    val expiresAt: String?,
    val summary: CamapsImportSummary,
)

data class CamapsCommit(
    val batchId: String,
    /** Vrai quand la même confirmation avait déjà abouti (reprise) : rien n'est en double. */
    val alreadyCommitted: Boolean,
)

/** La conduite à tenir après un refus du serveur (tableau du § 3 de P7). */
enum class CamapsImportErrorKind {
    /** Adresse ou jeton absents. */
    NOT_CONFIGURED,

    /** 400 : le fichier n'est pas un export Glooko lisible. Ne pas réessayer. */
    REJECTED_FILE,

    /** 401 / 403 : jeton absent, révoqué ou sans rôle `camaps-uploader`. Ne pas réessayer. */
    UNAUTHORIZED,

    /** 404 : module non activé ou serveur non migré (Nightscout classique). Ne pas réessayer. */
    NOT_ENABLED,

    /** 409 / 410 : aperçu périmé ou changé — refaire l'aperçu. */
    REDO_PREVIEW,

    /** 413 : fichier ou période trop grands. */
    TOO_LARGE,

    /** 415 : erreur de programmation de l'appli. */
    PROGRAMMING,

    /** 500 ou panne réseau : rejouer la même étape (rien n'est visible tant que ce n'est pas validé). */
    RETRY,

    /** Réponse illisible. */
    INVALID_RESPONSE,
}

class CamapsImportException(
    val kind: CamapsImportErrorKind,
    /** Le `code` du serveur (`PREVIEW_EXPIRED_OR_UNKNOWN`…), quand il y en a un. */
    val code: String?,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

interface CamapsImportApi {
    /** Aperçu du CSV [csv], envoyé **sans aucune transformation** (BOM et fins de ligne compris). */
    fun preview(credentials: NightscoutCredentials, csv: ByteArray): CamapsPreview

    /** Confirme [preview]. Rejouer la même confirmation est sans danger (même lot, aucun doublon). */
    fun commit(credentials: NightscoutCredentials, preview: CamapsPreview): CamapsCommit
}

class HttpCamapsImportApi @Inject constructor(
    private val transport: HttpTransport,
) : CamapsImportApi {

    override fun preview(credentials: NightscoutCredentials, csv: ByteArray): CamapsPreview {
        if (csv.size > MAX_BYTES) {
            throw CamapsImportException(CamapsImportErrorKind.TOO_LARGE, "FILE_TOO_LARGE", "Export Glooko de plus de 10 Mo.")
        }
        val response = send(
            HttpRequest(
                method = "POST",
                url = base(credentials) + PREVIEW_PATH,
                headers = mapOf("api-secret" to authorizationHeader(credentials)),
                bodyBytes = csv,
                contentType = "text/csv",
            ),
        )
        val json = parse(response.body)
        val summary = json.optJSONObject("summary") ?: JSONObject()
        return CamapsPreview(
            previewId = json.optString("previewId").ifBlank { invalid("previewId absent") },
            previewDigest = json.optString("previewDigest").ifBlank { invalid("previewDigest absent") },
            expiresAt = json.optString("expiresAt").ifBlank { null },
            summary = CamapsImportSummary(
                days = summary.optInt("days"),
                total = summary.optInt("total"),
                new = summary.optInt("new"),
                alreadyKnown = summary.optInt("alreadyKnown"),
                conflicts = summary.optInt("conflicts"),
                possibleCorrections = summary.optInt("possibleCorrections"),
                ambiguous = summary.optInt("ambiguous"),
                unsupported = summary.optInt("unsupported"),
                unmapped = summary.optInt("unmapped"),
                rejectedRows = summary.optInt("rejectedRows"),
            ),
        )
    }

    override fun commit(credentials: NightscoutCredentials, preview: CamapsPreview): CamapsCommit {
        val body = JSONObject()
            .put("previewId", preview.previewId)
            .put("previewDigest", preview.previewDigest)
            .toString()
        val response = send(
            HttpRequest(
                method = "POST",
                url = base(credentials) + COMMIT_PATH,
                headers = mapOf("api-secret" to authorizationHeader(credentials)),
                body = body,
                contentType = "application/json",
            ),
        )
        val json = parse(response.body)
        return CamapsCommit(
            batchId = json.optString("batchId").ifBlank { invalid("batchId absent") },
            alreadyCommitted = json.optBoolean("alreadyCommitted", false),
        )
    }

    private fun base(credentials: NightscoutCredentials): String {
        if (credentials.secret.isBlank()) {
            throw CamapsImportException(CamapsImportErrorKind.NOT_CONFIGURED, null, "Aucun jeton Nightscout enregistré.")
        }
        return NightscoutUrl.normalize(credentials.baseUrl)?.trimEnd('/')
            ?: throw CamapsImportException(CamapsImportErrorKind.NOT_CONFIGURED, null, "Adresse Nightscout absente ou invalide.")
    }

    private fun send(request: HttpRequest): HttpResponse {
        val response = try {
            guardTransport { transport.execute(request) }
        } catch (error: NightscoutException) {
            throw CamapsImportException(CamapsImportErrorKind.RETRY, null, error.message, error)
        }
        if (response.code in 200..299) return response
        val code = runCatching { JSONObject(response.body).optString("code").ifBlank { null } }.getOrNull()
        val kind = when (response.code) {
            400 -> CamapsImportErrorKind.REJECTED_FILE
            401, 403 -> CamapsImportErrorKind.UNAUTHORIZED
            404 -> CamapsImportErrorKind.NOT_ENABLED
            409, 410 -> CamapsImportErrorKind.REDO_PREVIEW
            413 -> CamapsImportErrorKind.TOO_LARGE
            415 -> CamapsImportErrorKind.PROGRAMMING
            in 500..599 -> CamapsImportErrorKind.RETRY
            else -> CamapsImportErrorKind.INVALID_RESPONSE
        }
        throw CamapsImportException(kind, code, messageFor(kind, response.code, code))
    }

    private fun parse(body: String): JSONObject = try {
        JSONObject(body)
    } catch (error: JSONException) {
        throw CamapsImportException(CamapsImportErrorKind.INVALID_RESPONSE, null, "Réponse illisible du serveur.", error)
    }

    private fun invalid(what: String): Nothing =
        throw CamapsImportException(CamapsImportErrorKind.INVALID_RESPONSE, null, "Réponse incomplète du serveur : $what.")

    internal companion object {
        const val PREVIEW_PATH = "/api/v1/camaps/imports/preview"
        const val COMMIT_PATH = "/api/v1/camaps/imports/commit"
        const val MAX_BYTES = 10_000_000

        fun messageFor(kind: CamapsImportErrorKind, http: Int, code: String?): String = when (kind) {
            CamapsImportErrorKind.REJECTED_FILE -> "Le serveur refuse ce fichier (${code ?: http}) : ce n'est pas un export Glooko lisible."
            CamapsImportErrorKind.UNAUTHORIZED -> "Jeton refusé ($http) : il faut un jeton de rôle « camaps-uploader »."
            CamapsImportErrorKind.NOT_ENABLED -> "Ce serveur n'est pas un Nightscout for CamAPS FX (404) : choisissez « Nightscout classique »."
            CamapsImportErrorKind.REDO_PREVIEW -> "Aperçu périmé (${code ?: http}) : à refaire."
            CamapsImportErrorKind.TOO_LARGE -> "Export trop volumineux (${code ?: http}) : réduisez la période."
            CamapsImportErrorKind.PROGRAMMING -> "Format d'envoi refusé (${code ?: http}) : erreur de l'appli."
            CamapsImportErrorKind.RETRY -> "Le serveur n'a pas pu traiter l'envoi ($http) : nouvel essai plus tard."
            else -> "Réponse inattendue du serveur ($http)."
        }
    }
}
