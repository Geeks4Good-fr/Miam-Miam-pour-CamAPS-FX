package com.najah.mealannouncer.feature.voice

import com.najah.mealannouncer.data.voxtral.VOXTRAL_TTS_MODEL
import com.najah.mealannouncer.data.voxtral.VoxtralAudioFormat
import com.najah.mealannouncer.data.voxtral.VoxtralVoice
import java.io.File
import java.security.MessageDigest

/**
 * Cache des phrases déjà synthétisées.
 *
 * Les annonces sont toujours les mêmes quelques phrases (« Repas annoncé : 62 grammes… ») : la
 * deuxième fois, l'audio sort du disque au lieu du réseau. La clé est l'empreinte de **tout ce
 * qui change le son** — texte, voix, modèle, format — pour qu'un changement de voix ne fasse
 * jamais réentendre l'ancienne.
 *
 * Purge : au-delà de [maxBytes], les fichiers les plus anciens partent d'abord. Un cache qui
 * grossit sans fin dans une application de santé est un défaut, pas un détail.
 */
internal class VoiceAudioCache(
    private val root: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    /** Fichier où doit vivre l'audio de cette phrase (qu'il existe déjà ou non). */
    fun fileFor(
        text: String,
        voice: VoxtralVoice,
        format: VoxtralAudioFormat = VoxtralAudioFormat.DEFAULT,
    ): File = File(root, "${key(text, voice, format)}.${format.wire}")

    /**
     * Écrit l'audio, puis purge. L'écriture passe par un fichier temporaire : une coupure en
     * cours de route ne doit pas laisser un MP3 tronqué qui serait rejoué ensuite.
     */
    fun write(
        text: String,
        voice: VoxtralVoice,
        format: VoxtralAudioFormat = VoxtralAudioFormat.DEFAULT,
        bytes: ByteArray,
    ): File {
        root.mkdirs()
        val target = fileFor(text, voice, format)
        val temporary = File(root, "${target.name}.part")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(target)) {
            target.delete()
            temporary.renameTo(target)
        }
        purge()
        return target
    }

    /** Supprime les plus anciens fichiers jusqu'à repasser sous [maxBytes]. */
    fun purge() {
        val files = root.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        // Du plus ancien au plus récent : ce qui sert le plus souvent survit.
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun key(
        text: String,
        voice: VoxtralVoice,
        format: VoxtralAudioFormat,
    ): String {
        val material = "$text|${voice.id}|$VOXTRAL_TTS_MODEL|${format.wire}"
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    internal companion object {
        const val DEFAULT_MAX_BYTES = 8L * 1024 * 1024
    }
}
