package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.keryx.app.theme.CinzelFamily
import kotlin.math.cos
import kotlin.math.sin

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
    // Centred when it fits; scrolls when it doesn't (large text on a short screen, landscape)
    // instead of cutting the button off.
    BoxWithConstraints(modifier.fillMaxSize()) {
        val floor = if (constraints.hasBoundedHeight) maxHeight else 0.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = floor)
                .padding(horizontal = 36.dp, vertical = 16.dp),
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
}

/**
 * The drawings share one hand: a 220 × 168 grid, the frame in the quiet line ink, one subject in
 * full ink, and the two accents (amber, violet) for the one or two things that matter.
 */
private class EmptyArt(private val scope: DrawScope, val k: Float, val ink: EmptyInk) {
    fun p(x: Float, y: Float) = Offset(x * k, y * k)

    fun card(
        x: Float, y: Float, w: Float, h: Float, radius: Float,
        edge: Color?, fill: Color? = ink.surface, width: Float = 2f, dashed: Boolean = false,
    ) {
        val corner = CornerRadius(radius * k)
        if (fill != null) scope.drawRoundRect(fill, p(x, y), Size(w * k, h * k), corner)
        if (edge == null) return
        val dash = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4f * k, 4f * k)) else null
        scope.drawRoundRect(edge, p(x, y), Size(w * k, h * k), corner, style = Stroke(width * k, pathEffect = dash))
    }

    fun line(color: Color, x1: Float, y1: Float, x2: Float, y2: Float, width: Float = 2f) =
        scope.drawLine(color, p(x1, y1), p(x2, y2), width * k, cap = StrokeCap.Round)

    fun dot(color: Color, x: Float, y: Float, r: Float) = scope.drawCircle(color, r * k, p(x, y))

    fun ring(color: Color, x: Float, y: Float, r: Float, width: Float = 2f, fill: Color? = null) {
        if (fill != null) scope.drawCircle(fill, r * k, p(x, y))
        scope.drawCircle(color, r * k, p(x, y), style = Stroke(width * k))
    }

    /** A path written in grid units. */
    fun stroke(color: Color, width: Float = 2.5f, build: Path.() -> Unit) =
        scope.drawPath(grid(build), color, style = Stroke(width * k, cap = StrokeCap.Round))

    fun fill(color: Color, build: Path.() -> Unit) = scope.drawPath(grid(build), color)

    private fun grid(build: Path.() -> Unit) =
        Path().apply { build(); transform(Matrix().apply { scale(k, k) }) }

    /** The herald's wings, as on the staff: two strokes out from one point. */
    fun wings(x: Float, y: Float, span: Float = 20f) = stroke(ink.ink) {
        moveTo(x, y); quadraticTo(x - span * 0.45f, y - 10f, x - span, y - 6f)
        moveTo(x, y); quadraticTo(x + span * 0.45f, y - 8f, x + span * 0.9f, y - 3f)
    }

    /** A few lines of writing, quiet. */
    fun writing(x: Float, y: Float, lengths: List<Float>, gap: Float = 12f) =
        lengths.forEachIndexed { i, len -> line(ink.line.copy(alpha = 0.22f), x, y + i * gap, x + len, y + i * gap) }
}

private class EmptyInk(val line: Color, val ink: Color, val surface: Color, val amber: Color, val violet: Color)

@Composable
private fun EmptyArtCanvas(description: String, modifier: Modifier, draw: EmptyArt.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ink = EmptyInk(
        line = scheme.onSurfaceVariant,
        ink = scheme.onSurface.copy(alpha = 0.8f),
        surface = scheme.surface,
        amber = scheme.primary,
        violet = scheme.tertiary,
    )
    Canvas(modifier.size(width = 220.dp, height = 168.dp).semantics { contentDescription = description }) {
        EmptyArt(this, size.width / 220f, ink).draw()
    }
}

/**
 * The empty mission board: three columns waiting, one dashed card, the herald's staff leaning on
 * the frame with its two serpents in the app's accents, and a first card set down in front.
 */
@Composable
fun MissionBoardIllustration(modifier: Modifier = Modifier) =
    EmptyArtCanvas("An empty mission board with the herald's staff leaning on it", modifier) {
        card(24f, 22f, 172f, 112f, 12f, ink.line.copy(alpha = 0.45f))
        for (x in listOf(81f, 139f)) line(ink.line.copy(alpha = 0.22f), x, 34f, x, 122f)
        for (x in listOf(36f, 94f, 152f)) card(x, 38f, 34f, 6f, 3f, edge = null, fill = ink.line.copy(alpha = 0.18f))
        card(36f, 54f, 34f, 22f, 5f, ink.line.copy(alpha = 0.3f), fill = null, dashed = true)
        // The staff, its orb and wings.
        line(ink.ink, 182f, 18f, 160f, 150f, width = 3f)
        dot(ink.ink, 182.6f, 14.5f, 4f)
        stroke(ink.ink) {
            moveTo(181f, 24f); quadraticTo(172f, 14f, 162f, 18f)
            moveTo(181f, 24f); quadraticTo(190f, 16f, 199f, 21f)
        }
        // Two serpents up the staff.
        stroke(ink.violet) {
            moveTo(163f, 140f); cubicTo(152f, 130f, 158f, 118f, 168f, 112f); cubicTo(178f, 106f, 180f, 94f, 171f, 86f)
        }
        stroke(ink.amber) {
            moveTo(163f, 140f); cubicTo(176f, 132f, 175f, 120f, 167f, 112f); cubicTo(159f, 104f, 160f, 92f, 172f, 86f)
        }
        // The first card, set down in front.
        card(70f, 144f, 64f, 16f, 5f, ink.amber)
        line(ink.amber, 78f, 152f, 110f, 152f)
    }

/** Runs, nothing scheduled: a clock with the staff's orb and wings, and the turn of a repeat. */
@Composable
fun RunsClockIllustration(modifier: Modifier = Modifier) =
    EmptyArtCanvas("A winged clock with an arrow curving round it", modifier) {
        val cx = 100f; val cy = 92f; val r = 44f
        ring(ink.line.copy(alpha = 0.45f), cx, cy, r, fill = ink.surface)
        for (h in 0 until 12) {
            val a = Math.toRadians(h * 30.0 - 90.0)
            val inner = if (h % 3 == 0) r - 10f else r - 7f
            line(
                ink.line.copy(alpha = 0.35f),
                cx + inner * cos(a).toFloat(), cy + inner * sin(a).toFloat(),
                cx + (r - 3f) * cos(a).toFloat(), cy + (r - 3f) * sin(a).toFloat(),
            )
        }
        // Ten past ten, the hands open like the wings above them.
        line(ink.ink, cx, cy, cx - 16f, cy - 10f, width = 3f)
        line(ink.ink, cx, cy, cx + 20f, cy - 22f, width = 2.5f)
        dot(ink.ink, cx, cy, 3f)
        // The finial: orb and wings, as on the staff.
        line(ink.ink, cx, cy - r, cx, cy - r - 8f, width = 3f)
        dot(ink.ink, cx, cy - r - 12f, 4f)
        wings(cx, cy - r - 6f, span = 26f)
        // The repeat: an amber arc round the right side, landing on the next run (violet).
        val ar = r + 12f
        stroke(ink.amber) {
            arcTo(
                androidx.compose.ui.geometry.Rect(cx - ar, cy - ar, cx + ar, cy + ar),
                startAngleDegrees = -40f, sweepAngleDegrees = 100f, forceMoveTo = true,
            )
        }
        val end = Math.toRadians(60.0)
        val tipX = cx + ar * cos(end).toFloat(); val tipY = cy + ar * sin(end).toFloat()
        // Clockwise tangent at the tip, and the two barbs of the arrowhead back along it.
        val tx = -sin(end).toFloat(); val ty = cos(end).toFloat()
        for (turn in listOf(0.6f, -0.6f)) {
            val bx = tx * cos(turn) - ty * sin(turn); val by = tx * sin(turn) + ty * cos(turn)
            line(ink.amber, tipX, tipY, tipX - bx * 8f, tipY - by * 8f, width = 2.5f)
        }
        val start = Math.toRadians(-40.0)
        dot(ink.violet, cx + ar * cos(start).toFloat(), cy + ar * sin(start).toFloat(), 4.5f)
    }

/** Bots, none yet: one head under the staff's orb and wings, an empty place either side. */
@Composable
fun BotsIllustration(modifier: Modifier = Modifier) =
    EmptyArtCanvas("A robot head topped with the herald's winged orb, an empty place either side", modifier) {
        ring(ink.line.copy(alpha = 0.3f), 34f, 92f, 20f)
        ring(ink.line.copy(alpha = 0.3f), 186f, 92f, 20f)
        line(ink.line.copy(alpha = 0.3f), 34f, 84f, 34f, 100f)
        line(ink.line.copy(alpha = 0.3f), 26f, 92f, 42f, 92f)
        line(ink.line.copy(alpha = 0.3f), 186f, 84f, 186f, 100f)
        line(ink.line.copy(alpha = 0.3f), 178f, 92f, 194f, 92f)
        card(70f, 62f, 80f, 62f, 16f, ink.ink, width = 2.5f)
        line(ink.ink, 110f, 62f, 110f, 40f, width = 2.5f)
        dot(ink.ink, 110f, 34f, 4f)
        wings(110f, 42f, span = 24f)
        dot(ink.amber, 94f, 90f, 6f)
        dot(ink.violet, 126f, 90f, 6f)
        line(ink.line.copy(alpha = 0.5f), 98f, 108f, 122f, 108f)
        // Its name tag, waiting to be written.
        card(84f, 136f, 52f, 16f, 5f, ink.amber)
        line(ink.amber, 92f, 144f, 116f, 144f)
    }

/** Projects, none yet: a workspace folder, a page in it, and a branch on the flap. */
@Composable
fun ProjectsFolderIllustration(modifier: Modifier = Modifier) =
    EmptyArtCanvas("A folder holding a page, with a branch drawn on it", modifier) {
        val frame = ink.line.copy(alpha = 0.45f)
        card(36f, 36f, 56f, 24f, 8f, frame)
        card(36f, 48f, 148f, 98f, 10f, frame)
        card(54f, 40f, 72f, 70f, 6f, ink.line.copy(alpha = 0.3f), fill = null, dashed = true)
        card(88f, 30f, 72f, 76f, 6f, ink.amber)
        line(ink.amber, 98f, 42f, 140f, 42f)
        writing(98f, 54f, listOf(44f, 30f))
        // The front flap, and a branch on it: most workspaces are a repo.
        card(28f, 84f, 164f, 66f, 10f, ink.ink, width = 2.5f)
        line(ink.violet, 100f, 104f, 100f, 132f, width = 2.5f)
        stroke(ink.violet) { moveTo(100f, 126f); cubicTo(100f, 116f, 120f, 120f, 120f, 108f) }
        ring(ink.violet, 100f, 100f, 4f, width = 2.5f, fill = ink.surface)
        ring(ink.violet, 100f, 136f, 4f, width = 2.5f, fill = ink.surface)
        ring(ink.violet, 120f, 104f, 4f, width = 2.5f, fill = ink.surface)
    }

/** Archive search, nothing indexed: a scroll and the lens over it. */
@Composable
fun ArchiveScrollIllustration(modifier: Modifier = Modifier) =
    EmptyArtCanvas("An unrolled scroll with a magnifying glass over it", modifier) {
        val frame = ink.line.copy(alpha = 0.45f)
        card(44f, 32f, 112f, 100f, 6f, frame)
        card(36f, 24f, 128f, 14f, 7f, frame)
        card(36f, 126f, 128f, 14f, 7f, frame)
        writing(58f, 54f, listOf(84f, 66f, 78f, 52f, 70f))
        // The lens: magnified writing, one line of it found (violet).
        line(ink.ink, 166f, 126f, 188f, 150f, width = 5f)
        ring(ink.amber, 150f, 108f, 22f, width = 3f, fill = ink.surface)
        line(ink.line.copy(alpha = 0.3f), 136f, 100f, 162f, 100f, width = 3f)
        line(ink.violet, 136f, 110f, 156f, 110f, width = 3f)
        line(ink.line.copy(alpha = 0.3f), 136f, 120f, 150f, 120f, width = 3f)
    }

/** Archive saved, nothing kept: a page with one line marked and the ribbon in it. */
@Composable
fun ArchiveBookmarkIllustration(modifier: Modifier = Modifier) =
    EmptyArtCanvas("A page with one line marked and a bookmark ribbon", modifier) {
        card(42f, 36f, 112f, 124f, 10f, ink.line.copy(alpha = 0.3f), fill = null, dashed = true)
        card(56f, 26f, 112f, 124f, 10f, ink.line.copy(alpha = 0.45f))
        writing(72f, 56f, listOf(64f, 74f))
        line(ink.amber, 72f, 80f, 140f, 80f, width = 3f)
        writing(72f, 92f, listOf(58f, 70f, 40f))
        fill(ink.violet) {
            moveTo(138f, 18f); lineTo(158f, 18f); lineTo(158f, 70f); lineTo(148f, 61f); lineTo(138f, 70f); close()
        }
    }

/** Archive media, nothing indexed: a picture in front of an empty frame, a file beside it. */
@Composable
fun ArchiveMediaIllustration(modifier: Modifier = Modifier) =
    EmptyArtCanvas("A framed picture of a hill and sun, with an empty frame behind it", modifier) {
        card(36f, 26f, 110f, 84f, 8f, ink.line.copy(alpha = 0.3f), fill = null, dashed = true)
        card(70f, 50f, 120f, 92f, 8f, ink.ink, width = 2.5f)
        fill(ink.violet.copy(alpha = 0.22f)) {
            moveTo(80f, 132f); lineTo(112f, 96f); lineTo(132f, 116f); lineTo(146f, 102f); lineTo(180f, 132f); close()
        }
        stroke(ink.violet) {
            moveTo(80f, 132f); lineTo(112f, 96f); lineTo(132f, 116f); lineTo(146f, 102f); lineTo(180f, 132f)
        }
        dot(ink.amber, 160f, 76f, 8f)
        // A file set down in front.
        card(26f, 126f, 52f, 30f, 5f, ink.amber)
        line(ink.amber, 34f, 141f, 64f, 141f)
    }
