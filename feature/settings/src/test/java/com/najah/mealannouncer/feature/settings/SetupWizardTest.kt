package com.najah.mealannouncer.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SetupWizardTest {

    @Test
    fun pkce_genere_un_verificateur_et_un_challenge_rfc7636_valides() {
        val verifier = OpenRouterPkce.generateCodeVerifier()
        assertTrue(
            "Le vérificateur PKCE doit mesurer 43 caractères URL-safe sans '='",
            verifier.length == 43 && !verifier.contains("="),
        )
        // Vecteur de test RFC 7636 Annexe B
        val rfcVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val rfcChallenge = OpenRouterPkce.computeCodeChallenge(rfcVerifier)
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            rfcChallenge,
        )
        val url = OpenRouterPkce.buildAuthUrl(rfcChallenge)
        assertTrue(
            "L'URL d'authentification OpenRouter doit contenir callback_url et S256",
            url.startsWith("https://openrouter.ai/auth?callback_url=miammiam%3A%2F%2Fopenrouter-auth") &&
                url.contains("code_challenge_method=S256") &&
                url.contains(rfcChallenge),
        )
    }

    @Test
    fun extraction_zip_retire_le_dossier_racine_connu_et_ecrit_les_fichiers() {
        val tempRoot = Files.createTempDirectory("miam-pack-test").toFile()
        try {
            val zipBytes = createZip(
                mapOf(
                    "vosk-model-small-fr-0.22/am/final.mdl" to "modele-acoustique".toByteArray(),
                    "vosk-model-small-fr-0.22/conf/mfcc.conf" to "sample-frequency=16000".toByteArray(),
                ),
            )
            var reportedBytes = 0L
            OfflinePacksInstaller.extractZipStream(
                input = ByteArrayInputStream(zipBytes),
                destRoot = tempRoot,
                totalUncompressedBytes = 39L,
                onExtractedBytes = { reportedBytes = it },
            )
            assertTrue(
                "Le fichier am/final.mdl doit être extrait directement sous la racine cible",
                File(tempRoot, "am/final.mdl").isFile,
            )
            assertTrue(
                "Le fichier conf/mfcc.conf doit être extrait directement sous la racine cible",
                File(tempRoot, "conf/mfcc.conf").isFile,
            )
            assertTrue(
                "Le compteur d'octets extraits doit refléter la taille décompressée",
                reportedBytes > 0L,
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun extraction_zip_refuse_la_traversee_de_chemin() {
        val tempRoot = Files.createTempDirectory("miam-zipslip-test").toFile()
        try {
            val zipBytes = createZip(
                mapOf(
                    "../echappe.txt" to "interdit".toByteArray(),
                ),
            )
            try {
                OfflinePacksInstaller.extractZipStream(
                    input = ByteArrayInputStream(zipBytes),
                    destRoot = tempRoot,
                )
                fail("Une entrée ZIP avec '../' doit lever SecurityException")
            } catch (expected: SecurityException) {
                assertTrue(
                    "Le message doit mentionner l'entrée fautive",
                    expected.message.orEmpty().contains("echappe.txt"),
                )
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun diagnostic_accessibilite_distingue_arrete_decroche_et_actif() {
        val pkg = "com.najah.mealannouncer"
        val svc = "com.najah.mealannouncer.integration.camaps.CamapsAccessibilityService"

        assertEquals(
            AccessibilityBindingState.OFF,
            AccessibilityDiagnostics.inspect(
                enabledServicesSetting = null,
                packageName = pkg,
                serviceClassName = svc,
                isRunning = false,
            ),
        )

        assertEquals(
            AccessibilityBindingState.UNBOUND_AFTER_UPDATE,
            AccessibilityDiagnostics.inspect(
                enabledServicesSetting = "com.autre/com.autre.Svc:$pkg/$svc",
                packageName = pkg,
                serviceClassName = svc,
                isRunning = false,
            ),
        )

        assertEquals(
            AccessibilityBindingState.UNBOUND_AFTER_UPDATE,
            AccessibilityDiagnostics.inspect(
                enabledServicesSetting = "$pkg/.integration.camaps.CamapsAccessibilityService",
                packageName = pkg,
                serviceClassName = svc,
                isRunning = false,
            ),
        )

        assertEquals(
            AccessibilityBindingState.BOUND_AND_RUNNING,
            AccessibilityDiagnostics.inspect(
                enabledServicesSetting = "$pkg/$svc",
                packageName = pkg,
                serviceClassName = svc,
                isRunning = true,
            ),
        )
    }

    private fun createZip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
