package com.najah.mealannouncer.integration.glooko

import com.najah.mealannouncer.data.nightscout.CamapsImportApi
import com.najah.mealannouncer.data.nightscout.CamapsImportErrorKind
import com.najah.mealannouncer.data.nightscout.CamapsImportException
import com.najah.mealannouncer.data.nightscout.NightscoutCredentials
import javax.inject.Inject

/**
 * Où partent les données exportées de Glooko (choix de l'utilisateur, 29/10/2026).
 *
 * - [CLASSIC_NIGHTSCOUT] : un Nightscout ordinaire. L'appli garde sa propre conversion du CSV
 *   ([GlookoToNightscoutMapper] → [GlookoImporter]) et écrit les documents Nightscout habituels.
 * - [CAMAPS_NIGHTSCOUT] : un **Nightscout for CamAPS FX** (le fork). Le CSV part **intact** ;
 *   c'est le serveur qui l'interprète (contrat P7). Rien n'est écrit dans `entries`,
 *   `treatments` ni `profile`.
 */
enum class GlookoDestination {
    CLASSIC_NIGHTSCOUT,
    CAMAPS_NIGHTSCOUT,
}

/** Compte rendu d'un envoi au fork, en une phrase. */
data class CamapsUploadReport(
    val ok: Boolean,
    val dryRun: Boolean,
    val summary: String,
)

/**
 * Envoie un export Glooko à un Nightscout for CamAPS FX : aperçu, puis confirmation.
 *
 * Conduite du § 3 de P7 : une simulation s'arrête après l'aperçu (rien n'est visible) ; une
 * confirmation perdue (réseau, 500) est **rejouée telle quelle** — même lot, aucun doublon ; un
 * aperçu périmé ou changé (409 / 410) est refait une fois. Les autres refus ne se réessaient pas.
 */
class CamapsGlookoUploader @Inject constructor(
    private val api: CamapsImportApi,
) {

    fun upload(credentials: NightscoutCredentials, csv: ByteArray, dryRun: Boolean): CamapsUploadReport {
        var preview = try {
            api.preview(credentials, csv)
        } catch (error: CamapsImportException) {
            return failure(dryRun, error)
        }
        if (dryRun) {
            return CamapsUploadReport(true, true, "Vérifié sans importer : ${preview.summary.text()}")
        }
        var previewRedone = false
        var commitReplays = 0
        while (true) {
            try {
                val commit = api.commit(credentials, preview)
                val again = if (commit.alreadyCommitted) " (lot déjà importé, rien en double)" else ""
                return CamapsUploadReport(true, false, "Importé dans Nightscout for CamAPS FX$again : ${preview.summary.text()}")
            } catch (error: CamapsImportException) {
                when {
                    error.kind == CamapsImportErrorKind.RETRY && commitReplays < MAX_COMMIT_REPLAYS -> commitReplays++
                    error.kind == CamapsImportErrorKind.REDO_PREVIEW && !previewRedone -> {
                        previewRedone = true
                        preview = try {
                            api.preview(credentials, csv)
                        } catch (again: CamapsImportException) {
                            return failure(false, again)
                        }
                    }
                    else -> return failure(false, error)
                }
            }
        }
    }

    private fun failure(dryRun: Boolean, error: CamapsImportException) =
        CamapsUploadReport(false, dryRun, "Nightscout for CamAPS FX : ${error.message}")

    internal companion object {
        /** Une confirmation perdue est rejouée deux fois au plus, puis le prochain passage reprend. */
        const val MAX_COMMIT_REPLAYS = 2
    }
}
