package chat.keryx.app.presentation.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import chat.keryx.app.theme.CinzelFamily

/**
 * The Keryx brand wordmark: classical Cinzel caps with a warm amber→gold sheen and a touch of
 * tracking. Used in the app bar and the drawer identity header so the brand reads as one mark.
 */
@Composable
fun KeryxWordmark(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 20.sp,
) {
    val accent = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.colorScheme.tertiary
    // Sheen runs from a lit accent-1 head into an accent-2 tail so the brand mark carries the
    // same two-accent gradient as the rest of the app's animations.
    val sheen = Brush.linearGradient(
        listOf(
            lerp(accent, Color.White, 0.35f),
            accent,
            lerp(accent, accent2, 0.65f),
        )
    )
    Text(
        text = "KERYX",
        modifier = modifier,
        style = TextStyle(
            brush = sheen,
            fontFamily = CinzelFamily,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            letterSpacing = (fontSize.value * 0.14f).sp,
        ),
    )
}
