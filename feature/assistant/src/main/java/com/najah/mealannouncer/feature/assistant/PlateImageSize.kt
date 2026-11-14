package com.najah.mealannouncer.feature.assistant

/**
 * Facteur de réduction d'une photo, par sous-échantillonnage.
 *
 * Une photo de téléphone fait 12 à 50 millions de pixels ; le modèle n'a rien à faire de cette
 * résolution et l'envoi coûterait plusieurs mégaoctets. On divise donc par deux tant que le
 * résultat reste au-dessus du côté long voulu, puis on ajuste finement une seule fois. Le
 * sous-échantillonnage se fait au décodage : la mémoire n'est jamais occupée par l'image
 * entière.
 *
 * Fonction pure, sans Android : c'est la partie de la réduction qu'on peut éprouver.
 */
internal fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
    if (maxSide <= 0 || width <= 0 || height <= 0) return 1
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= maxSide) {
        sample *= 2
    }
    return sample
}
