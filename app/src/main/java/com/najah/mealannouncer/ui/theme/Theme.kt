package com.najah.mealannouncer.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Thème « aquarelle » de Miam-Miam pour CamAPS FX (10/11/2026, demande de l'utilisateur) : papier
 * aquarelle crème ([PaperBackground]), encres vert feuille, rouge tomate et ocre agrume, cartes
 * légèrement transparentes pour laisser voir le grain, coins arrondis.
 *
 * Les contrastes restent élevés (encre foncée sur papier clair) : l'application se lit debout,
 * souvent avec une lumière forte, et parfois par une personne fatiguée.
 */
internal val PapierCreme = Color(0xFFFBF3E2)
private val Feuille = Color(0xFF2F6B1F)
private val Tomate = Color(0xFFB0392D)
private val Ocre = Color(0xFF94580A)

private val LightColors = lightColorScheme(
    primary = Feuille,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5EEC2),
    onPrimaryContainer = Color(0xFF0D2A04),
    secondary = Tomate,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFADCD4),
    onSecondaryContainer = Color(0xFF3E0702),
    tertiary = Ocre,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBE4B4),
    onTertiaryContainer = Color(0xFF2E1A00),
    background = PapierCreme,
    onBackground = Color(0xFF2E2619),
    // Cartes et champs : papier un peu plus clair, à peine transparent, comme une feuille posée.
    surface = Color(0xF2FFFBF2),
    onSurface = Color(0xFF2E2619),
    surfaceVariant = Color(0xE6FFF4DC),
    onSurfaceVariant = Color(0xFF574A33),
    outline = Color(0xFF8E7C5E),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8D88E),
    onPrimary = Color(0xFF173A08),
    primaryContainer = Color(0xFF2F5320),
    onPrimaryContainer = Color(0xFFD5EEC2),
    secondary = Color(0xFFFFB4A6),
    onSecondary = Color(0xFF5F150C),
    secondaryContainer = Color(0xFF7E2B20),
    onSecondaryContainer = Color(0xFFFADCD4),
    tertiary = Color(0xFFFFC66B),
    onTertiary = Color(0xFF4A2A00),
    tertiaryContainer = Color(0xFF6A3F00),
    onTertiaryContainer = Color(0xFFFBE4B4),
    background = Color(0xFF221D15),
    onBackground = Color(0xFFEDE1CC),
    surface = Color(0xF2282219),
    onSurface = Color(0xFFEDE1CC),
    surfaceVariant = Color(0xE63A3224),
    onSurfaceVariant = Color(0xFFD9C8A8),
    outline = Color(0xFFA2927A),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

/** Des coins bien ronds : plus doux, plus « fruit ». */
private val SpringShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Titres plus gras ; le corps de texte ne change pas (lisibilité). */
private val SpringTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Bold),
    )
}

@Composable
fun MealAnnouncerTheme(
    // Le papier aquarelle est clair par nature : on le garde même quand le téléphone est en mode
    // sombre (essai du 10/11/2026 : en sombre, le crème disparaissait et les lavis saturaient).
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = SpringShapes,
        typography = SpringTypography,
        content = content,
    )
}
