package chat.keryx.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val HermesDarkColorScheme = darkColorScheme(
    primary = HermesAmber,
    secondary = HermesAmber,
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onPrimary = TextPrimaryDark,
    onSecondary = TextPrimaryDark,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark,
    onSurfaceVariant = TextSecondaryDark
)

private val HermesLightColorScheme = lightColorScheme(
    primary = HermesAmber,
    secondary = HermesAmber,
    background = BackgroundLight,
    surface = SurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onPrimary = TextPrimaryLight,
    onSecondary = TextPrimaryLight,
    onBackground = TextPrimaryLight,
    onSurface = TextPrimaryLight,
    onSurfaceVariant = TextSecondaryLight
)

/** The default second accent — a dusk violet that pairs with the amber default for the
 *  sunset-gradient look (cloud banner, bubble gradients, gradient borders). User-overridable. */
val HermesDusk = androidx.compose.ui.graphics.Color(0xFF8B5CF6)

@Composable
fun HermesChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    customAccent: androidx.compose.ui.graphics.Color = HermesAmber,
    customAccent2: androidx.compose.ui.graphics.Color = HermesDusk,
    content: @Composable () -> Unit
) {
    val baseScheme = if (darkTheme) {
        HermesDarkColorScheme
    } else {
        HermesLightColorScheme
    }

    // Accent 2 rides the Material tertiary slot so every composable can reach it via
    // MaterialTheme.colorScheme.tertiary without new plumbing.
    val colorScheme = baseScheme.copy(
        primary = customAccent,
        secondary = customAccent,
        tertiary = customAccent2
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
