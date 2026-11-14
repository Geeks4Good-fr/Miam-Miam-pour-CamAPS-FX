package com.najah.mealannouncer.data.openfoodfacts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Une photo de produit n'est acceptée que si elle vient des serveurs d'images d'Open Food Facts. */
class OffImageTest {

    @Test
    fun `accepte les images d'Open Food Facts en https`() {
        assertTrue(
            isOffImage(
                "https://images.openfoodfacts.org/images/products/301/762/042/2003/front_fr.1.200.jpg",
            ),
        )
    }

    @Test
    fun `refuse une image d'un autre site ou en clair`() {
        assertFalse(isOffImage("https://exemple.fr/pain.jpg"))
        assertFalse(isOffImage("http://images.openfoodfacts.org/x.jpg"))
        assertFalse(isOffImage("https://images.openfoodfacts.org.pirate.com/x.jpg"))
        assertFalse(isOffImage(""))
        assertFalse(isOffImage(null))
    }
}
