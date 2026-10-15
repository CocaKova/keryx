package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.keryx.app.theme.CinzelFamily

/**
 * A space with nothing in it yet (3.0 redesign): a small line drawing, a Cinzel title, one
 * sentence on what goes here, and the one thing to do about it. Was a one-liner under the
 * braille snake, which also ran forever on an empty screen.
 */
@Composable
fun KeryxEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    illustration: (@Composable () -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (illustration != null) {
            illustration()
            Spacer(Modifier.height(20.dp))
        }
        Text(
            title,
            fontFamily = CinzelFamily,
            fontSize = KeryxType.display,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            fontSize = KeryxType.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(22.dp))
            val fill = keryxAccentInk()
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = contrastColorFor(fill)),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(actionLabel, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * The empty mission board: three columns waiting, one dashed card, the herald's staff leaning on
 * the frame with its two serpents in the app's accents, and a first card set down in front.
 */
@Composable
fun MissionBoardIllustration(modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val amber = MaterialTheme.colorScheme.primary
    val violet = MaterialTheme.colorScheme.tertiary
    Canvas(
        modifier
            .size(width = 220.dp, height = 168.dp)
            .semantics { contentDescription = "An empty mission board with the herald's staff leaning on it" },
    ) {
        // Drawn on a 220 × 168 grid, scaled to the canvas.
        val k = size.width / 220f
        fun p(x: Float, y: Float) = Offset(x * k, y * k)
        val w2 = 2f * k
        drawRoundRect(surface, p(24f, 22f), Size(172f * k, 112f * k), CornerRadius(12f * k))
        drawRoundRect(line.copy(alpha = 0.45f), p(24f, 22f), Size(172f * k, 112f * k), CornerRadius(12f * k), style = Stroke(w2))
        for (x in listOf(81f, 139f)) drawLine(line.copy(alpha = 0.22f), p(x, 34f), p(x, 122f), w2)
        for (x in listOf(36f, 94f, 152f)) drawRoundRect(line.copy(alpha = 0.18f), p(x, 38f), Size(34f * k, 6f * k), CornerRadius(3f * k))
        drawRoundRect(
            line.copy(alpha = 0.3f), p(36f, 54f), Size(34f * k, 22f * k), CornerRadius(5f * k),
            style = Stroke(w2, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * k, 4f * k))),
        )
        // The staff, its orb and wings.
        drawLine(ink.copy(alpha = 0.8f), p(182f, 18f), p(160f, 150f), 3f * k, cap = StrokeCap.Round)
        drawCircle(ink.copy(alpha = 0.8f), 4f * k, p(182.6f, 14.5f))
        val wings = Path().apply {
            moveTo(181f * k, 24f * k); quadraticTo(172f * k, 14f * k, 162f * k, 18f * k)
            moveTo(181f * k, 24f * k); quadraticTo(190f * k, 16f * k, 199f * k, 21f * k)
        }
        drawPath(wings, ink.copy(alpha = 0.8f), style = Stroke(2.5f * k, cap = StrokeCap.Round))
        // Two serpents up the staff.
        val s1 = Path().apply {
            moveTo(163f * k, 140f * k)
            cubicTo(176f * k, 132f * k, 175f * k, 120f * k, 167f * k, 112f * k)
            cubicTo(159f * k, 104f * k, 160f * k, 92f * k, 172f * k, 86f * k)
        }
        val s2 = Path().apply {
            moveTo(163f * k, 140f * k)
            cubicTo(152f * k, 130f * k, 158f * k, 118f * k, 168f * k, 112f * k)
            cubicTo(178f * k, 106f * k, 180f * k, 94f * k, 171f * k, 86f * k)
        }
        drawPath(s2, violet, style = Stroke(2.5f * k, cap = StrokeCap.Round))
        drawPath(s1, amber, style = Stroke(2.5f * k, cap = StrokeCap.Round))
        // The first card, set down in front.
        drawRoundRect(surface, p(70f, 144f), Size(64f * k, 16f * k), CornerRadius(5f * k))
        drawRoundRect(amber, p(70f, 144f), Size(64f * k, 16f * k), CornerRadius(5f * k), style = Stroke(w2))
        drawLine(amber, p(78f, 152f), p(110f, 152f), w2, cap = StrokeCap.Round)
    }
}
