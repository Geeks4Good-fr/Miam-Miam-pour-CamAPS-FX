package com.najah.mealannouncer.feature.assistant

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emplacement du modèle acoustique français Vosk (`vosk-model-small-fr-0.22`) sur le téléphone.
 *
 * Même patron que `OffDatabase` : le modèle (~40 Mo) vit dans le dossier externe de l'application
 * (`Android/data/<paquet>/files/vosk-model-fr`) afin de ne pas alourdir l'APK à chaque compilation.
 */
@Singleton
class VoskModelStorage @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext

    /** Dossier attendu du modèle Vosk français. */
    fun directory(): File = File(
        appContext.getExternalFilesDir(null) ?: appContext.filesDir,
        DIR_NAME,
    )

    /** Vrai si le modèle Vosk français est présent et complet sur l'appareil. */
    val available: Boolean
        get() {
            val dir = directory()
            return dir.isDirectory &&
                File(dir, "am").isDirectory &&
                File(dir, "conf").isDirectory &&
                File(dir, "graph").isDirectory
        }

    companion object {
        const val DIR_NAME = "vosk-model-fr"
    }
}
