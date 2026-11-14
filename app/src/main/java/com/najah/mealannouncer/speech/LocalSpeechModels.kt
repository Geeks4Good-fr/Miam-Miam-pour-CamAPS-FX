package com.najah.mealannouncer.speech

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emplacement des modèles de la voix locale (docs/VOIX-LOCALE.md).
 *
 * Même patron que le modèle Vosk et la base Open Food Facts : les modèles (≈ 160 Mo) vivent dans
 * le dossier externe de l'application et y sont copiés une fois par adb, au lieu d'alourdir
 * l'APK à chaque compilation :
 *
 * - `Android/data/<paquet>/files/sherpa/kroko-fr/` : `encoder.onnx`, `decoder.onnx`,
 *   `joiner.onnx`, `tokens.txt` (sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06) ;
 * - `Android/data/<paquet>/files/sherpa/piper-fr-upmc/` : `fr_FR-upmc-medium.onnx`,
 *   `tokens.txt`, dossier `espeak-ng-data` (vits-piper-fr_FR-upmc-medium).
 */
@Singleton
class LocalSpeechModels @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val root = File(context.getExternalFilesDir(null) ?: context.filesDir, ROOT_DIR)

    val krokoDir: File = File(root, KROKO_DIR)
    val piperDir: File = File(root, PIPER_DIR)

    val krokoAvailable: Boolean
        get() = KROKO_FILES.all { File(krokoDir, it).isFile }

    /** Le fichier `.onnx` de la voix Piper, s'il est là. */
    val piperModel: File?
        get() = piperDir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".onnx") }

    val piperAvailable: Boolean
        get() = piperModel != null &&
            File(piperDir, "tokens.txt").isFile &&
            File(piperDir, "espeak-ng-data").isDirectory

    companion object {
        const val ROOT_DIR = "sherpa"
        const val KROKO_DIR = "kroko-fr"
        const val PIPER_DIR = "piper-fr-upmc"
        val KROKO_FILES = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")
    }
}
