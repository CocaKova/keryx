package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.res.stringResource
import chat.keryx.app.R
import kotlin.math.atan2

// Colour: the accent picker and the heralds' lights. Split out of SettingsDialog.kt in 2.10
// (Phase E): a move, zero behaviour change.

/** A quick-tap starting set; the disc/slider/hex refine from there. */
internal val SWATCHES = listOf(
    0xFFE55A00, 0xFFE0A458, 0xFF8B5CF6, 0xFFE53935, 0xFFEC4899,
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
    KeryxSectionHeader(stringResource(R.string.settings_heralds), modifier = Modifier.padding(bottom = 6.dp))
    Text(
        stringResource(R.string.settings_heralds_blurb),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = KeryxType.micro,
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
            HeraldSigil(light, fontSize = KeryxType.titleLarge)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(key, fontSize = KeryxType.bodyLarge, fontWeight = FontWeight.Medium, color = light.accent)
                Text(
                    when {
                        light.primary -> stringResource(R.string.settings_herald_primary)
                        overrides.containsKey(key) -> stringResource(R.string.settings_herald_custom)
                        else -> stringResource(R.string.settings_herald_derived)
                    },
                    fontSize = KeryxType.micro,
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
                    Text(stringResource(R.string.settings_herald_reset))
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
            Text(stringResource(R.string.settings_brightness), fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    fontSize = KeryxType.body,
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

/** Accent pairs that already go together (makeover): one tap sets both halves of the gradient. */
internal val ACCENT_PALETTES: List<Triple<Int, Color, Color>> = listOf(
    Triple(R.string.settings_palette_ember, Color(0xFFE55A00), Color(0xFF8B5CF6)),
    Triple(R.string.settings_palette_gilded, Color(0xFFE0A458), Color(0xFFB45309)),
    Triple(R.string.settings_palette_nightshade, Color(0xFF8205E5), Color(0xFF00FFFA)),
    Triple(R.string.settings_palette_tidewater, Color(0xFF0EA5E9), Color(0xFF10B981)),
    Triple(R.string.settings_palette_rose, Color(0xFFEC4899), Color(0xFFF59E0B)),
    Triple(R.string.settings_palette_moss, Color(0xFF65A30D), Color(0xFF0D9488)),
    Triple(R.string.settings_palette_frost, Color(0xFF94A3B8), Color(0xFF60A5FA)),
    Triple(R.string.settings_palette_crimson, Color(0xFFDC2626), Color(0xFF7C3AED)),
)

private fun Color.same(other: Color) = (toArgb() and 0xFFFFFF) == (other.toArgb() and 0xFFFFFF)

/** The palettes as split circles, the picked one ringed and named. */
@Composable
internal fun AccentPalettes(accent: Color, accent2: Color, onPick: (Color, Color) -> Unit) {
    val picked = ACCENT_PALETTES.firstOrNull { (_, a, b) -> a.same(accent) && b.same(accent2) }
    Column(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(ACCENT_PALETTES.size) { i ->
                val (name, a, b) = ACCENT_PALETTES[i]
                val on = picked?.first == name
                val ring by androidx.compose.animation.core.animateDpAsState(if (on) 3.dp else 0.dp, label = "paletteRing")
                val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .keryxPressScale(source, scale = 0.9f)
                        .clip(CircleShape)
                        .border(ring, MaterialTheme.colorScheme.onSurface, CircleShape)
                        .padding(ring + 2.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(0.5f to a, 0.5f to b))
                        .clickable(interactionSource = source, indication = androidx.compose.material3.ripple()) { onPick(a, b) },
                ) {}
            }
        }
        Text(
            picked?.first?.let { stringResource(it) } ?: stringResource(R.string.settings_palette_own),
            fontSize = KeryxType.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * One accent (makeover): preset circles, then a rainbow circle that opens the full picker. The
 * wheel is for the one colour you can't find, not the first thing on the page.
 */
@Composable
internal fun AccentRow(label: String, current: Color, onSelected: (Color) -> Unit, discSize: androidx.compose.ui.unit.Dp) {
    var custom by remember { mutableStateOf(false) }
    val presets = SWATCHES
    val isPreset = presets.any { it.same(current) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = KeryxType.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            presets.forEach { c ->
                val on = c.same(current)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(c)
                        .clickable { onSelected(c); custom = false },
                ) {
                    androidx.compose.animation.AnimatedVisibility(on, enter = keryxPop(), exit = keryxVanish()) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(contrastColorFor(c)))
                    }
                }
            }
            // The custom circle: rainbow, ringed when the current colour is no preset.
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .border(if (!isPreset) 2.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .background(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
                    .clickable { custom = !custom },
            )
        }
        androidx.compose.animation.AnimatedVisibility(custom, enter = keryxReveal(), exit = keryxConceal()) {
            ColorPickerPanel(
                current = current,
                onColorSelected = onSelected,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                discSize = discSize,
            )
        }
    }
}
