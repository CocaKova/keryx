package chat.keryx.app.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import chat.keryx.app.R

/** Cinzel — a classical Roman-capital face; the Keryx brand wordmark font (nods to Hermes). */
val CinzelFamily = FontFamily(
    Font(R.font.cinzel, FontWeight.Normal),
    Font(R.font.cinzel, FontWeight.Bold),
)

// Set of Material typography styles to start with
val Typography =
  Typography(
    bodyLarge =
      TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
      )
    /* Other default text styles to override
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
    */
  )

/**
 * Every style reads its direction from its own first letter (3.0): an English line in an Arabic
 * or Hebrew app stays left-to-right, so its full stop and its "2:" stay at its end, and Arabic in
 * an English app runs right-to-left. Text with no letters at all follows the layout. This is what
 * a plain Android TextView does (FIRSTSTRONG); Compose's own default follows the layout only.
 */
internal fun Typography.readingItsOwnDirection(): Typography {
    fun TextStyle.own() = copy(textDirection = TextDirection.Content)
    return copy(
        displayLarge = displayLarge.own(), displayMedium = displayMedium.own(), displaySmall = displaySmall.own(),
        headlineLarge = headlineLarge.own(), headlineMedium = headlineMedium.own(), headlineSmall = headlineSmall.own(),
        titleLarge = titleLarge.own(), titleMedium = titleMedium.own(), titleSmall = titleSmall.own(),
        bodyLarge = bodyLarge.own(), bodyMedium = bodyMedium.own(), bodySmall = bodySmall.own(),
        labelLarge = labelLarge.own(), labelMedium = labelMedium.own(), labelSmall = labelSmall.own(),
    )
}
