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
    val sheen = Brush.linearGradient(
        listOf(
            lerp(accent, Color.White, 0.35f),
            accent,
            lerp(accent, Color(0xFF7A2E00), 0.25f),
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
