package com.najah.mealannouncer.feature.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Le sous-échantillonnage d'une photo d'assiette.
 *
 * Une photo de téléphone fait 12 à 50 millions de pixels ; on divise par deux au décodage tant
 * que le côté long reste au-dessus de la cible, pour ne jamais tenir l'image entière en mémoire.
 * Ce qui compte ici : le facteur est toujours une puissance de deux (imposé par `inSampleSize`),
 * il ne descend jamais sous la cible, et il ne la dépasse pas inutilement.
 */
class PlateImageSizeTest {

    @Test
    fun `une photo de telephone est reduite par puissances de deux`() {
        // 4000 / 2 = 2000, encore au-dessus de 1024 ; 4000 / 4 = 1000, en dessous : on s'arrête.
        assertEquals(2, sampleSizeFor(width = 4000, height = 3000, maxSide = 1024))
        // 8000 : 4000 puis 2000 puis 1000 → 4.
        assertEquals(4, sampleSizeFor(width = 8000, height = 6000, maxSide = 1024))
        // Portrait : c'est le côté long qui décide.
        assertEquals(2, sampleSizeFor(width = 3000, height = 4000, maxSide = 1024))
    }

    @Test
    fun `une photo deja petite n est pas reduite`() {
        assertEquals(1, sampleSizeFor(width = 1024, height = 768, maxSide = 1024))
        assertEquals(1, sampleSizeFor(width = 800, height = 600, maxSide = 1024))
    }

    @Test
    fun `une photo exactement au double de la cible est encore reduite`() {
        // 2048 / 2 = 1024, la cible est atteinte : réduire ne perd rien et divise la mémoire par 4.
        assertEquals(2, sampleSizeFor(width = 2048, height = 1536, maxSide = 1024))
    }

    @Test
    fun `le facteur ne depasse jamais ce qui est utile`() {
        // Une vignette minuscule ne doit pas être réduite jusqu'à disparaître.
        assertEquals(1, sampleSizeFor(width = 64, height = 48, maxSide = 1024))
        // Et le résultat reste une puissance de deux, quelle que soit la taille d'entrée.
        for (cote in listOf(1000, 1500, 3000, 4032, 9248)) {
            val facteur = sampleSizeFor(width = cote, height = cote, maxSide = 1024)
            assertEquals("facteur pour $cote", 0, facteur and (facteur - 1))
        }
    }

    @Test
    fun `des dimensions absurdes ne font rien de travers`() {
        assertEquals(1, sampleSizeFor(width = 0, height = 3000, maxSide = 1024))
        assertEquals(1, sampleSizeFor(width = 4000, height = 0, maxSide = 1024))
        assertEquals(1, sampleSizeFor(width = 4000, height = 3000, maxSide = 0))
        assertEquals(1, sampleSizeFor(width = -4000, height = 3000, maxSide = 1024))
    }
}
