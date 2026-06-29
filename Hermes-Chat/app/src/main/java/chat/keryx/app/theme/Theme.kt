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

@Composable
fun HermesChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    customAccent: androidx.compose.ui.graphics.Color = HermesAmber,
    content: @Composable () -> Unit
) {
    val baseScheme = if (darkTheme) {
        HermesDarkColorScheme
    } else {
        HermesLightColorScheme
    }

    val colorScheme = baseScheme.copy(
        primary = customAccent,
        secondary = customAccent
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
