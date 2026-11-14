package com.najah.mealannouncer.feature.voice

import com.najah.mealannouncer.data.voxtral.VoxtralAudioFormat
import com.najah.mealannouncer.data.voxtral.VoxtralVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Le cache des phrases : mêmes mots, même fichier ; un mot de plus, un autre fichier.
 *
 * Sans cela, changer de voix ou de phrase ferait réentendre l'ancienne — un défaut qui ne se
 * voit qu'à l'oreille, donc jamais dans un test qui se contenterait de vérifier l'existence
 * du fichier.
 */
class VoiceAudioCacheTest {

    private fun tempDir(): File = Files.createTempDirectory("voxtral-cache").toFile()

    @Test
    fun `la meme phrase donne le meme fichier`() {
        val cache = VoiceAudioCache(tempDir())
        val phrase = "Repas annoncé : 62 grammes de glucides."

        assertEquals(
            cache.fileFor(phrase, VoxtralVoice.MARIE),
            cache.fileFor(phrase, VoxtralVoice.MARIE),
        )
    }

    @Test
    fun `changer de voix ou de texte change de fichier`() {
        val cache = VoiceAudioCache(tempDir())
        val phrase = "Repas annoncé : 62 grammes de glucides."

        val reference = cache.fileFor(phrase, VoxtralVoice.MARIE)
        assertNotEquals(reference, cache.fileFor(phrase, VoxtralVoice.LEA))
        assertNotEquals(reference, cache.fileFor("Repas annoncé : 63 grammes de glucides.", VoxtralVoice.MARIE))
        assertNotEquals(reference, cache.fileFor(phrase, VoxtralVoice.MARIE, VoxtralAudioFormat.WAV))
    }

    @Test
    fun `l'extension suit le format demande`() {
        val cache = VoiceAudioCache(tempDir())
        val phrase = "Repas annoncé : 30 grammes de glucides."

        assertEquals("mp3", cache.fileFor(phrase, VoxtralVoice.MARIE).extension)
        assertEquals("wav", cache.fileFor(phrase, VoxtralVoice.MARIE, VoxtralAudioFormat.WAV).extension)
    }

    @Test
    fun `ecrire puis relire rend le meme fichier avec l'audio`() {
        val cache = VoiceAudioCache(tempDir())
        val phrase = "Repas annoncé : 12 grammes de glucides."
        val audio = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00)

        val file = cache.write(phrase, VoxtralVoice.CAMILLE, bytes = audio)

        assertTrue(file.isFile)
        assertEquals(audio.toList(), file.readBytes().toList())
        assertEquals(cache.fileFor(phrase, VoxtralVoice.CAMILLE), file)
    }

    @Test
    fun `la purge garde ce qui tient dans le budget`() {
        val root = tempDir()
        val cache = VoiceAudioCache(root, maxBytes = 300L)
        val audio = ByteArray(100) { 1 }

        repeat(4) { index ->
            val file = cache.write("phrase $index", VoxtralVoice.MARIE, bytes = audio)
            // Un fichier par phrase, dans l'ordre du temps : les plus anciens partent d'abord.
            file.setLastModified(1_000L + index * 1_000L)
        }
        cache.purge()

        val total = root.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        assertTrue("total après purge : $total", total <= 300L)
        // Le plus récent survit : c'est celui qui a le plus de chances de resservir.
        assertTrue(cache.fileFor("phrase 3", VoxtralVoice.MARIE).isFile)
    }
}
