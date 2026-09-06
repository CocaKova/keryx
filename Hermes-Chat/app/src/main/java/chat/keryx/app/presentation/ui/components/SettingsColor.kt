package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.keryx.app.presentation.ChatViewModel
import kotlin.math.atan2

// Colour: the accent picker and the heralds' lights. Split out of SettingsDialog.kt in 2.10
// (Phase E): a move, zero behaviour change.

/** A quick-tap starting set; the disc/slider/hex refine from there. */
internal val SWATCHES = listOf(
    0xFFE0A458, 0xFF8B5CF6, 0xFFE53935, 0xFFEC4899,
    0xFF3B82F6, 0xFF10B981, 0xFFF59E0B, 0xFF94A3B8,
).map { Color(it) }

/**
 * Full HSV picker: hue/saturation disc (angle = hue, radius = saturation) + brightness slider +
 * editable hex field + preset swatches. The old wheel only ever emitted `hsv(hue, 1, 1)` — pure
 * neon hues — which is why precise colors were unreachable.
 */
/**
 * Settings → Connection → "Heralds" (2.3 §1). One row per configured agent, each showing the light
 * it will actually wear in a room. The primary herald is read-only — it borrows the user's own
 * accents by design, so a 1:1 room looks exactly like 2.2 and there is nothing separate to pick.
 */
@Composable
internal fun HeraldsList(
    agentMatrixId: String,
    overrides: Map<String, String>,
    onSetAccent: (String, String?) -> Unit,
) {
    val ids = chat.keryx.core.model.Heralds.parseIds(agentMatrixId)
    if (ids.size < 2) return // One agent is not a council; the row would say nothing.

    var editing by remember { mutableStateOf<String?>(null) }

    Spacer(Modifier.height(14.dp))
    KeryxSectionHeader("Heralds", modifier = Modifier.padding(bottom = 6.dp))
    Text(
        "Each life has its own color. Tap a herald to choose its light, or reset it to the one " +
            "Keryx derived from its name.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
    )
    Spacer(Modifier.height(8.dp))

    ids.forEach { id ->
        val key = chat.keryx.core.model.Heralds.localpart(id)
        val light = heraldLightFor(id, "")
        val open = editing == key
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !light.primary) { editing = if (open) null else key }
                .padding(vertical = 8.dp),
        ) {
            HeraldSigil(light, fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(key, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = light.accent)
                Text(
                    when {
                        light.primary -> "Primary — wears your own accents"
                        overrides.containsKey(key) -> "Your colour"
                        else -> "Derived from the name"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(listOf(light.accent, light.accent2))
                    )
            )
        }
        if (open) {
            ColorPickerPanel(
                current = light.accent,
                onColorSelected = { c ->
                    onSetAccent(key, String.format("#%06X", 0xFFFFFF and c.toArgb()))
                },
                modifier = Modifier.fillMaxWidth(),
                discSize = 150.dp,
            )
            if (overrides.containsKey(key)) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onSetAccent(key, null) }) {
                    Text("Reset to derived colour")
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
fun ColorPickerPanel(
    current: Color,
    onColorSelected: (Color) -> Unit,
    modifier: Modifier = Modifier,
    discSize: androidx.compose.ui.unit.Dp = 180.dp,
) {
    var hue by remember { mutableStateOf(0f) }
    var sat by remember { mutableStateOf(1f) }
    var bright by remember { mutableStateOf(1f) }
    var lastEmitted by remember { mutableStateOf<Int?>(null) }
    var hexText by remember { mutableStateOf("") }
    var hexFocused by remember { mutableStateOf(false) }

    fun deriveFrom(color: Color) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        hue = hsv[0]; sat = hsv[1]; bright = hsv[2]
    }
    // External change (reset button, dialog reopen) re-derives; our own echo must not, or a
    // gray/desaturated pick would snap the hue slider back to 0.
    LaunchedEffect(current) {
        if (current.toArgb() != lastEmitted) deriveFrom(current)
        if (!hexFocused) hexText = String.format("%06X", 0xFFFFFF and current.toArgb())
    }
    fun apply(color: Color, rederive: Boolean) {
        if (rederive) deriveFrom(color)
        lastEmitted = color.toArgb()
        onColorSelected(color)
    }
    fun emitHsv() = apply(Color.hsv(hue, sat, bright), rederive = false)

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // ── Hue/saturation disc ──
        var center by remember { mutableStateOf(Offset.Zero) }
        var radiusPx by remember { mutableStateOf(1f) }
        fun pick(offset: Offset) {
            val dx = offset.x - center.x
            val dy = offset.y - center.y
            val degrees = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat().let { if (it < 0) it + 360f else it }
            hue = degrees
            sat = kotlin.math.min(1f, kotlin.math.sqrt(dx * dx + dy * dy) / radiusPx)
            emitHsv()
        }
        Canvas(
            modifier = Modifier
                .size(discSize)
                .pointerInput(Unit) { detectTapGestures { pick(it) } }
                .pointerInput(Unit) { detectDragGestures { change, _ -> pick(change.position) } },
        ) {
            center = Offset(size.width / 2, size.height / 2)
            radiusPx = size.minDimension / 2
            val hues = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
            drawCircle(brush = Brush.sweepGradient(hues, center), radius = radiusPx, center = center)
            // White core → saturation falls toward the center.
            drawCircle(
                brush = Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, radiusPx),
                radius = radiusPx,
                center = center,
            )
            // Selector ring at the current hue/sat position, filled with the actual color.
            val ang = Math.toRadians(hue.toDouble())
            val selPos = Offset(
                center.x + (sat * radiusPx * kotlin.math.cos(ang)).toFloat(),
                center.y + (sat * radiusPx * kotlin.math.sin(ang)).toFloat(),
            )
            drawCircle(Color.hsv(hue, sat, bright), radius = 9.dp.toPx(), center = selPos)
            drawCircle(Color.White, radius = 9.dp.toPx(), center = selPos, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
        Spacer(Modifier.height(10.dp))

        // ── Brightness ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Brightness", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Slider(
                value = bright,
                onValueChange = { bright = it; emitHsv() },
                valueRange = 0.15f..1f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = Color.hsv(hue, sat, bright),
                    activeTrackColor = Color.hsv(hue, sat, 1f),
                ),
            )
        }

        // ── Exact color: hex in, swatches for quick starts ──
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = hexText,
                onValueChange = { raw ->
                    val filtered = raw.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(6).uppercase()
                    hexText = filtered
                    if (filtered.length == 6) {
                        filtered.toLongOrNull(16)?.let { apply(Color(0xFF000000L or it), rederive = true) }
                    }
                },
                prefix = { Text("#", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 13.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                modifier = Modifier
                    .width(118.dp)
                    .onFocusChanged { hexFocused = it.isFocused },
            )
            SWATCHES.forEach { swatch ->
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(KeryxRadius.field))
                        .background(swatch)
                        .pointerInput(swatch) { detectTapGestures { apply(swatch, rederive = true) } },
                )
            }
        }
    }
}
