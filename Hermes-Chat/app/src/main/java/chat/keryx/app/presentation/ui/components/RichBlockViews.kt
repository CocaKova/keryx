package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import chat.keryx.core.protocol.RichBlock
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The drawn half of [RichBlock] (2.17): what a ```chart, ```diff, ```timeline, ```progress,
 * ```swatch, ```card or ```details fence looks like in a bubble.
 *
 * Every block sits on the same quiet surface as a code block — the bubble's own ink at low alpha,
 * rounded — so a chart reads as part of the message, not a widget pasted into it. Charts are
 * drawn here on a Canvas rather than through a chart library: the shapes an agent asks for are
 * few, and owning them keeps the palette, the motion and the type scale Keryx's.
 *
 * Motion: one short draw-in when a block first composes, held still under Battery Saver. Nothing
 * here loops.
 *
 * [renderMarkdown] is the bubble's own markdown path, for the parts of a block that are prose
 * (a card's body, a details section) — so they get links, code and nested blocks like any text.
 */
@Composable
fun RichBlockView(
    block: RichBlock,
    textColor: Color,
    renderMarkdown: @Composable (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (block) {
        is RichBlock.Chart -> ChartBlock(block, textColor, modifier)
        is RichBlock.Diff -> DiffBlock(block, textColor, modifier)
        is RichBlock.Timeline -> TimelineBlock(block, textColor, modifier)
        is RichBlock.Progress -> ProgressBlock(block, textColor, modifier)
        is RichBlock.Swatches -> SwatchBlock(block, textColor, modifier)
        is RichBlock.Card -> CardBlock(block, textColor, renderMarkdown, modifier)
        is RichBlock.Details -> DetailsBlock(block, textColor, renderMarkdown, modifier)
    }
}

// --- shared ------------------------------------------------------------------------------------

private val BlockShape = RoundedCornerShape(KeryxRadius.field)

/** The quiet surface every block stands on: the bubble's ink at code-block alpha. */
private fun Modifier.blockSurface(textColor: Color): Modifier = this
    .fillMaxWidth()
    .padding(vertical = 4.dp)
    .clip(BlockShape)
    .background(textColor.copy(alpha = 0.05f))

/**
 * 0 → 1 once per block per app run; already 1 under reduced motion. A row scrolled out of the
 * list loses its `remember`, so "once" is kept here — scrolling back to a chart must not replay
 * its entrance.
 */
@Composable
private fun rememberDrawIn(key: Any, durationMs: Int = 700): Float {
    val reduced by rememberReducedMotion()
    val seen = remember(key) { DrawnOnce.seen(key) }
    val progress = remember(key) { Animatable(if (reduced || seen) 1f else 0f) }
    LaunchedEffect(key, reduced) {
        if (reduced || seen) progress.snapTo(1f)
        else progress.animateTo(1f, tween(durationMs, easing = KeryxMotion.arcane))
        DrawnOnce.mark(key)
    }
    return progress.value
}

private object DrawnOnce {
    private val keys = object : LinkedHashMap<Any, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, Unit>) = size > 256
    }
    fun seen(key: Any): Boolean = synchronized(keys) { keys.containsKey(key) }
    fun mark(key: Any) = synchronized(keys) { keys[key] = Unit }
}

/** Is the bubble light (dark ink)? Charts darken their hues there and lift them on the void. */
private fun Color.isInkDark(): Boolean = luminance() < 0.5f

/**
 * Series colours: the theme's three accents, then the primary's hue walked round the wheel,
 * each pressed toward the bubble's ink until it reads on the bubble — darker on paper, lifted
 * on the void.
 */
@Composable
private fun chartPalette(textColor: Color): List<Color> {
    val cs = MaterialTheme.colorScheme
    val onLight = textColor.isInkDark()
    return remember(cs.primary, cs.tertiary, cs.secondary, onLight) {
        val base = listOf(cs.primary, cs.tertiary, cs.secondary)
        val walk = listOf(150f, 210f, 270f, 30f, 90f, 330f, 180f, 240f, 300f).map { rotateHue(cs.primary, it) }
        (base + walk).distinctBy { it.value }.map { c ->
            if (onLight) lerp(c, Color.Black, (c.luminance() - 0.35f).coerceIn(0f, 0.45f))
            else lerp(c, Color.White, (0.45f - c.luminance()).coerceIn(0f, 0.4f))
        }
    }
}

private fun rotateHue(c: Color, degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(), hsv)
    hsv[0] = (hsv[0] + degrees) % 360f
    // A grey primary has no hue to walk; give the walk something to see.
    if (hsv[1] < 0.25f) hsv[1] = 0.55f
    if (hsv[2] < 0.45f) hsv[2] = 0.75f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** 1234 → "1,234"; 12 500 → "12.5k"; 0.125 → "0.13". Compact enough for an axis. */
internal fun chartNumber(v: Double): String {
    val a = abs(v)
    fun trim(x: Double, digits: Int): String {
        val s = String.format(java.util.Locale.ROOT, "%.${digits}f", x)
        return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    }
    return when {
        a >= 1e9 -> trim(v / 1e9, 1) + "B"
        a >= 1e6 -> trim(v / 1e6, 1) + "M"
        a >= 1e4 -> trim(v / 1e3, 1) + "k"
        a >= 100 -> String.format(java.util.Locale.ROOT, "%,d", v.toLong())
        a >= 1 -> trim(v, 1)
        a == 0.0 -> "0"
        else -> trim(v, 2)
    }
}

/** The unit sits on the number when it is a symbol (ms, %, $) and in the title when it is a word. */
private fun shortUnit(unit: String?): Boolean = unit != null && unit.length <= 3

private fun withUnit(v: Double, unit: String?): String {
    val n = chartNumber(v)
    if (!shortUnit(unit)) return n
    return when (unit) {
        "$", "€", "£", "¥" -> unit + n
        "%" -> "$n%"
        else -> "$n $unit"
    }
}

/** A "nice" axis: [lo, hi] widened to round steps, about [target] of them. */
private data class Axis(val lo: Double, val hi: Double, val step: Double) {
    val ticks: List<Double> get() {
        val out = ArrayList<Double>()
        var t = lo
        var guard = 0
        while (t <= hi + step * 1e-6 && guard++ < 20) { out += t; t += step }
        return out
    }
    fun frac(v: Double): Float = if (hi == lo) 0f else ((v - lo) / (hi - lo)).toFloat()
}

private fun niceAxis(minV: Double, maxV: Double, target: Int = 4, includeZero: Boolean = true): Axis {
    var lo = if (includeZero) min(0.0, minV) else minV
    var hi = if (includeZero) max(0.0, maxV) else maxV
    if (hi == lo) { lo -= 1.0; hi += 1.0 }
    if (!includeZero) { val pad = (hi - lo) * 0.12; lo -= pad; hi += pad }
    val raw = (hi - lo) / target
    val mag = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
    lo = floor(lo / step) * step
    hi = ceil(hi / step) * step
    return Axis(lo, hi, step)
}

private fun chartSummary(c: RichBlock.Chart): String = buildString {
    append(c.title ?: "Chart").append(". ")
    append(c.type.name.lowercase()).append(" chart")
    c.unit?.let { append(", in ").append(it) }
    append(". ")
    c.series.forEach { s ->
        s.name?.let { append(it).append(": ") }
        append(c.labels.zip(s.values).joinToString(", ") { (l, v) -> "$l ${v?.let(::chartNumber) ?: "no value"}" })
        append(". ")
    }
}

@Composable
private fun axisStyle(textColor: Color) = TextStyle(
    color = textColor.copy(alpha = 0.55f),
    fontSize = KeryxType.micro,
    fontFamily = FontFamily.Monospace,
)

private fun TextMeasurer.one(text: String, style: TextStyle, maxWidth: Int = Int.MAX_VALUE): TextLayoutResult =
    measure(
        AnnotatedString(text), style,
        overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1,
        constraints = Constraints(maxWidth = maxWidth.coerceAtLeast(1)),
    )

// --- chart -------------------------------------------------------------------------------------

@Composable
private fun ChartBlock(c: RichBlock.Chart, textColor: Color, modifier: Modifier) {
    val palette = chartPalette(textColor)
    val progress = rememberDrawIn(c)
    val summary = remember(c) { chartSummary(c) }
    Column(modifier = modifier.blockSurface(textColor).padding(12.dp)) {
        val title = listOfNotNull(c.title, c.unit?.takeIf { !shortUnit(it) }?.let { "($it)" }).joinToString(" ")
        if (title.isNotBlank()) {
            Text(title, color = textColor, fontSize = KeryxType.body, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
        }
        val chartModifier = Modifier.fillMaxWidth().semantics { contentDescription = summary }
        when (c.type) {
            RichBlock.ChartType.BAR -> BarChart(c, palette, textColor, progress, chartModifier.height(190.dp))
            RichBlock.ChartType.HBAR -> HBarChart(c, palette, textColor, progress, chartModifier)
            RichBlock.ChartType.LINE, RichBlock.ChartType.AREA ->
                LineChart(c, palette, textColor, progress, area = c.type == RichBlock.ChartType.AREA, modifier = chartModifier.height(180.dp))
            RichBlock.ChartType.PIE, RichBlock.ChartType.DONUT -> PieChart(c, palette, textColor, progress, chartModifier)
        }
        val pie = c.type == RichBlock.ChartType.PIE || c.type == RichBlock.ChartType.DONUT
        if (!pie && c.series.size > 1) {
            Spacer(Modifier.height(8.dp))
            Legend(c.series.mapIndexed { i, s -> (s.name ?: "Series ${i + 1}") to palette[i % palette.size] }, textColor)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(entries: List<Pair<String, Color>>, textColor: Color) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        entries.forEach { (name, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(5.dp))
                Text(name, color = textColor.copy(alpha = 0.75f), fontSize = KeryxType.micro, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Gridlines and their labels on a vertical value axis; returns the plot's left edge. */
private fun DrawScope.valueGrid(
    axis: Axis, unit: String?, measurer: TextMeasurer, style: TextStyle,
    top: Float, bottom: Float, textColor: Color,
): Float {
    val labels = axis.ticks.map { it to measurer.one(withUnit(it, unit), style) }
    val gutter = (labels.maxOfOrNull { it.second.size.width } ?: 0) + 6.dp.toPx()
    labels.forEach { (v, layout) ->
        val y = bottom - axis.frac(v) * (bottom - top)
        drawLine(
            textColor.copy(alpha = if (v == 0.0) 0.22f else 0.08f),
            Offset(gutter, y), Offset(size.width, y),
            strokeWidth = 1.dp.toPx(),
            pathEffect = if (v == 0.0) null else PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
        )
        drawText(layout, topLeft = Offset(gutter - 6.dp.toPx() - layout.size.width, y - layout.size.height / 2f))
    }
    return gutter
}

/** Category labels under a plot, thinned so they never overlap. */
private fun DrawScope.categoryLabels(
    labels: List<String>, measurer: TextMeasurer, style: TextStyle,
    left: Float, bandW: Float, y: Float, centered: Boolean,
) {
    if (labels.isEmpty()) return
    val maxW = (bandW * 2.2f).toInt().coerceAtLeast(24)
    val layouts = labels.map { measurer.one(it, style, maxW) }
    val widest = layouts.maxOf { it.size.width } + 6.dp.toPx()
    val every = max(1, ceil(widest / bandW).toInt())
    layouts.forEachIndexed { i, layout ->
        if (i % every != 0) return@forEachIndexed
        val cx = if (centered) left + bandW * (i + 0.5f) else left + bandW * i
        val x = (cx - layout.size.width / 2f).coerceIn(left - 4.dp.toPx(), size.width - layout.size.width)
        drawText(layout, topLeft = Offset(x, y))
    }
}

@Composable
private fun BarChart(c: RichBlock.Chart, palette: List<Color>, textColor: Color, progress: Float, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val style = axisStyle(textColor)
    val valueStyle = style.copy(color = textColor.copy(alpha = 0.8f))
    val n = c.labels.size
    val axis = remember(c) {
        if (c.stacked) {
            val pos = (0 until n).map { i -> c.series.sumOf { (it.values[i] ?: 0.0).coerceAtLeast(0.0) } }
            val neg = (0 until n).map { i -> c.series.sumOf { (it.values[i] ?: 0.0).coerceAtMost(0.0) } }
            niceAxis(neg.minOrNull() ?: 0.0, pos.maxOrNull() ?: 0.0)
        } else {
            val all = c.series.flatMap { it.values }.filterNotNull()
            niceAxis(all.min(), all.max())
        }
    }
    Canvas(modifier) {
        val labelH = measurer.one("Ag", style).size.height.toFloat()
        val top = labelH / 2f + 2.dp.toPx()
        val bottom = size.height - labelH - 6.dp.toPx()
        val left = valueGrid(axis, c.unit, measurer, style, top, bottom, textColor)
        val bandW = (size.width - left) / n
        val zeroY = bottom - axis.frac(0.0) * (bottom - top)
        val radius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
        val showValues = !c.stacked && n * c.series.size <= 12
        for (i in 0 until n) {
            val bandLeft = left + bandW * i
            if (c.stacked) {
                val w = (bandW * 0.56f).coerceAtMost(48.dp.toPx())
                var up = 0.0
                var down = 0.0
                c.series.forEachIndexed { s, series ->
                    val v = (series.values[i] ?: return@forEachIndexed) * progress
                    val from = if (v >= 0) up else down
                    val to = from + v
                    if (v >= 0) up = to else down = to
                    val y1 = bottom - axis.frac(from) * (bottom - top)
                    val y2 = bottom - axis.frac(to) * (bottom - top)
                    drawRoundRect(
                        palette[s % palette.size],
                        topLeft = Offset(bandLeft + (bandW - w) / 2f, min(y1, y2)),
                        size = Size(w, abs(y2 - y1)),
                        cornerRadius = if (s == c.series.lastIndex) radius else CornerRadius.Zero,
                    )
                }
            } else {
                val groupW = (bandW * 0.72f).coerceAtMost(c.series.size * 40.dp.toPx())
                val barW = groupW / c.series.size
                val start = bandLeft + (bandW - groupW) / 2f
                c.series.forEachIndexed { s, series ->
                    val raw = series.values[i] ?: return@forEachIndexed
                    val v = raw * progress
                    val y = bottom - axis.frac(v) * (bottom - top)
                    val x = start + barW * s + barW * 0.08f
                    val w = barW * 0.84f
                    drawRoundRect(
                        palette[s % palette.size],
                        topLeft = Offset(x, min(y, zeroY)),
                        size = Size(w, abs(zeroY - y).coerceAtLeast(1f)),
                        cornerRadius = radius,
                    )
                    if (showValues && progress >= 1f) {
                        val layout = measurer.one(chartNumber(raw), valueStyle)
                        if (layout.size.width <= barW * 1.4f) {
                            val ty = if (raw >= 0) y - layout.size.height - 2.dp.toPx() else y + 2.dp.toPx()
                            drawText(layout, topLeft = Offset(x + w / 2f - layout.size.width / 2f, ty.coerceAtLeast(0f)))
                        }
                    }
                }
            }
        }
        categoryLabels(c.labels, measurer, style, left, bandW, bottom + 4.dp.toPx(), centered = true)
    }
}

@Composable
private fun HBarChart(c: RichBlock.Chart, palette: List<Color>, textColor: Color, progress: Float, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val style = axisStyle(textColor).copy(fontFamily = FontFamily.Default, color = textColor.copy(alpha = 0.75f))
    val valueStyle = axisStyle(textColor).copy(color = textColor.copy(alpha = 0.8f))
    val n = c.labels.size
    val rowH = if (c.series.size > 1 && !c.stacked) 14.dp * c.series.size + 10.dp else 26.dp
    val axis = remember(c) {
        if (c.stacked) {
            val pos = (0 until n).map { i -> c.series.sumOf { (it.values[i] ?: 0.0).coerceAtLeast(0.0) } }
            niceAxis(0.0, pos.maxOrNull() ?: 0.0)
        } else {
            val all = c.series.flatMap { it.values }.filterNotNull()
            niceAxis(all.min(), all.max())
        }
    }
    Canvas(modifier.height(rowH * n + 4.dp)) {
        val labelW = min(size.width * 0.34f, (c.labels.maxOf { measurer.one(it, style).size.width } + 8.dp.toPx()))
        val valueW = measurer.one(withUnit(axis.hi, c.unit), valueStyle).size.width + 6.dp.toPx()
        val left = labelW
        val right = size.width - valueW
        val plotW = (right - left).coerceAtLeast(1f)
        val rowPx = rowH.toPx()
        val zeroX = left + axis.frac(0.0) * plotW
        val radius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
        for (i in 0 until n) {
            val rowTop = rowPx * i
            val label = measurer.one(c.labels[i], style, (labelW - 8.dp.toPx()).toInt())
            drawText(label, topLeft = Offset(0f, rowTop + (rowPx - label.size.height) / 2f))
            if (c.stacked) {
                var acc = 0.0
                val h = (rowPx * 0.56f)
                c.series.forEachIndexed { s, series ->
                    val v = ((series.values[i] ?: return@forEachIndexed).coerceAtLeast(0.0)) * progress
                    val x1 = left + axis.frac(acc) * plotW
                    acc += v
                    val x2 = left + axis.frac(acc) * plotW
                    drawRoundRect(
                        palette[s % palette.size],
                        topLeft = Offset(x1, rowTop + (rowPx - h) / 2f), size = Size(x2 - x1, h),
                        cornerRadius = if (s == c.series.lastIndex) radius else CornerRadius.Zero,
                    )
                }
                val total = c.series.sumOf { (it.values[i] ?: 0.0).coerceAtLeast(0.0) }
                if (progress >= 1f) {
                    val t = measurer.one(withUnit(total, c.unit), valueStyle)
                    drawText(t, topLeft = Offset(left + axis.frac(total) * plotW + 4.dp.toPx(), rowTop + (rowPx - t.size.height) / 2f))
                }
            } else {
                val barH = ((rowPx - 10.dp.toPx()) / c.series.size).coerceAtMost(16.dp.toPx())
                val groupTop = rowTop + (rowPx - barH * c.series.size) / 2f
                c.series.forEachIndexed { s, series ->
                    val raw = series.values[i] ?: return@forEachIndexed
                    val x = left + axis.frac(raw * progress) * plotW
                    val y = groupTop + barH * s
                    drawRoundRect(
                        palette[s % palette.size],
                        topLeft = Offset(min(x, zeroX), y + barH * 0.1f),
                        size = Size(abs(x - zeroX).coerceAtLeast(1f), barH * 0.8f),
                        cornerRadius = radius,
                    )
                    if (progress >= 1f) {
                        val t = measurer.one(withUnit(raw, c.unit), valueStyle)
                        val tx = if (raw >= 0) x + 4.dp.toPx() else (x - 4.dp.toPx() - t.size.width).coerceAtLeast(left)
                        drawText(t, topLeft = Offset(tx.coerceAtMost(size.width - t.size.width), y + (barH - t.size.height) / 2f))
                    }
                }
            }
        }
        drawLine(textColor.copy(alpha = 0.22f), Offset(zeroX, 0f), Offset(zeroX, rowPx * n), strokeWidth = 1.dp.toPx())
    }
}

@Composable
private fun LineChart(
    c: RichBlock.Chart, palette: List<Color>, textColor: Color, progress: Float, area: Boolean, modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val style = axisStyle(textColor)
    val n = c.labels.size
    val axis = remember(c) {
        val all = c.series.flatMap { it.values }.filterNotNull()
        // A line's axis need not start at zero: data that sits far above it (a temperature,
        // a price) would be a flat line along the top. Anchor at zero only when it is near.
        val lo = all.min()
        val hi = all.max()
        if (lo > 0 && lo > (hi - lo) * 1.5) niceAxis(lo, hi, includeZero = false) else niceAxis(lo, hi)
    }
    val showDots = n <= 40
    Canvas(modifier) {
        val labelH = measurer.one("Ag", style).size.height.toFloat()
        val top = labelH / 2f + 2.dp.toPx()
        val bottom = size.height - labelH - 6.dp.toPx()
        val left = valueGrid(axis, c.unit, measurer, style, top, bottom, textColor) + 4.dp.toPx()
        val right = size.width - 4.dp.toPx()
        val stepX = if (n > 1) (right - left) / (n - 1) else 0f
        fun px(i: Int) = if (n > 1) left + stepX * i else (left + right) / 2f
        fun py(v: Double) = bottom - axis.frac(v) * (bottom - top)
        clipRect(right = left + (right - left) * progress + 6.dp.toPx()) {
            c.series.forEachIndexed { s, series ->
                val color = palette[s % palette.size]
                // Runs of consecutive non-null points; a null is a gap in the line.
                val runs = mutableListOf<MutableList<Int>>()
                series.values.forEachIndexed { i, v ->
                    if (v == null) runs += mutableListOf<Int>() else {
                        if (runs.isEmpty()) runs += mutableListOf<Int>()
                        runs.last() += i
                    }
                }
                runs.filter { it.isNotEmpty() }.forEach { run ->
                    val path = Path()
                    run.forEachIndexed { k, i ->
                        val x = px(i)
                        val y = py(series.values[i]!!)
                        if (k == 0) path.moveTo(x, y) else {
                            val pi = run[k - 1]
                            val x0 = px(pi)
                            val y0 = py(series.values[pi]!!)
                            val mx = (x0 + x) / 2f
                            path.cubicTo(mx, y0, mx, y, x, y)
                        }
                    }
                    if (area && run.size > 1) {
                        val fill = Path().apply {
                            addPath(path)
                            lineTo(px(run.last()), bottom)
                            lineTo(px(run.first()), bottom)
                            close()
                        }
                        drawPath(
                            fill,
                            Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0.02f)), startY = top, endY = bottom),
                        )
                    }
                    drawPath(path, color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))
                    if (showDots || run.size == 1) run.forEach { i ->
                        val center = Offset(px(i), py(series.values[i]!!))
                        drawCircle(color, radius = 3.dp.toPx(), center = center)
                        drawCircle(hollowOf(color), radius = 1.4.dp.toPx(), center = center)
                    }
                }
            }
        }
        val bandW = if (n > 1) stepX else (right - left)
        categoryLabels(c.labels, measurer, style, if (n > 1) left else left + (right - left) / 2f - bandW / 2f, bandW, bottom + 4.dp.toPx(), centered = n <= 1)
    }
}

/** A point's hollow: its own colour lightened or darkened, so it reads on any bubble. */
private fun hollowOf(dot: Color): Color =
    if (dot.isInkDark()) lerp(dot, Color.White, 0.55f) else lerp(dot, Color.Black, 0.35f)

@Composable
private fun PieChart(c: RichBlock.Chart, palette: List<Color>, textColor: Color, progress: Float, modifier: Modifier) {
    val values = c.series.single().values.map { (it ?: 0.0).coerceAtLeast(0.0) }
    val total = values.sum()
    val donut = c.type == RichBlock.ChartType.DONUT
    val measurer = rememberTextMeasurer()
    val centreStyle = TextStyle(color = textColor, fontSize = KeryxType.title, fontWeight = FontWeight.SemiBold)
    val centreSub = axisStyle(textColor)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(132.dp)) {
            val d = min(size.width, size.height)
            val stroke = if (donut) d * 0.2f else 0f
            val inset = stroke / 2f
            val topLeft = Offset((size.width - d) / 2f + inset, (size.height - d) / 2f + inset)
            val arcSize = Size(d - stroke, d - stroke)
            var start = -90f
            // Donut segments part by a hair; a pie's slices meet.
            val gap = if (donut && values.count { it > 0 } > 1) 1.2f else 0f
            values.forEachIndexed { i, v ->
                if (v <= 0) return@forEachIndexed
                val sweep = (v / total * 360.0).toFloat() * progress
                val color = palette[i % palette.size]
                if (donut) drawArc(color, start + gap / 2f, (sweep - gap).coerceAtLeast(0.5f), useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
                else drawArc(color, start, sweep, useCenter = true, topLeft = topLeft, size = arcSize)
                start += sweep
            }
            if (donut) {
                val big = measurer.one(withUnit(total, c.unit), centreStyle)
                val small = measurer.one("total", centreSub)
                val h = big.size.height + small.size.height
                drawText(big, topLeft = Offset(size.width / 2f - big.size.width / 2f, size.height / 2f - h / 2f))
                drawText(small, topLeft = Offset(size.width / 2f - small.size.width / 2f, size.height / 2f - h / 2f + big.size.height))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
            c.labels.forEachIndexed { i, label ->
                val v = values[i]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(palette[i % palette.size]))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        label, color = textColor.copy(alpha = 0.85f), fontSize = KeryxType.caption,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${chartNumber(v / total * 100)}%",
                        color = textColor.copy(alpha = 0.55f), fontSize = KeryxType.micro, fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

// --- diff --------------------------------------------------------------------------------------

private const val DIFF_FOLD = 40

@Composable
private fun DiffBlock(d: RichBlock.Diff, textColor: Color, modifier: Modifier) {
    val clipboard = LocalClipboardManager.current
    val good = KeryxStatus.good
    val bad = KeryxStatus.bad
    val accent = keryxAccentInk()
    var expanded by remember(d) { mutableStateOf(false) }
    var copied by remember(d) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1600); copied = false } }
    val shown = if (expanded || d.lines.size <= DIFF_FOLD + 5) d.lines else d.lines.take(DIFF_FOLD)
    val added = d.lines.count { it.kind == RichBlock.DiffKind.ADD }
    val removed = d.lines.count { it.kind == RichBlock.DiffKind.DEL }
    Column(modifier = modifier.blockSurface(textColor)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 4.dp),
        ) {
            Text("diff", color = textColor.copy(alpha = 0.45f), fontSize = KeryxType.micro, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text("+$added", color = good, fontSize = KeryxType.micro, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Text("−$removed", color = bad, fontSize = KeryxType.micro, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (copied) "✓" else "❐",
                color = if (copied) good else textColor.copy(alpha = 0.45f),
                fontSize = KeryxType.body,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(d.lines.joinToString("\n") { it.text }))
                        copied = true
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            val minW = maxWidth
            // The app's swipe rule: a scroller only claims drags when there is somewhere to go.
            val scroll = rememberScrollState()
            Column(
                Modifier
                    .horizontalScroll(scroll, enabled = scroll.maxValue > 0)
                    .widthIn(min = minW)
                    .width(IntrinsicSize.Max),
            ) {
                shown.forEach { line ->
                    val (bg, ink) = when (line.kind) {
                        RichBlock.DiffKind.ADD -> good.copy(alpha = 0.13f) to textColor
                        RichBlock.DiffKind.DEL -> bad.copy(alpha = 0.13f) to textColor
                        RichBlock.DiffKind.HUNK -> accent.copy(alpha = 0.08f) to accent
                        RichBlock.DiffKind.META -> Color.Transparent to textColor.copy(alpha = 0.45f)
                        RichBlock.DiffKind.CONTEXT -> Color.Transparent to textColor.copy(alpha = 0.78f)
                    }
                    val gutter = when (line.kind) {
                        RichBlock.DiffKind.ADD -> "+" to good
                        RichBlock.DiffKind.DEL -> "−" to bad
                        else -> " " to Color.Transparent
                    }
                    val body = when (line.kind) {
                        RichBlock.DiffKind.ADD, RichBlock.DiffKind.DEL -> line.text.drop(1)
                        RichBlock.DiffKind.CONTEXT -> line.text.removePrefix(" ")
                        else -> line.text
                    }
                    Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 6.dp)) {
                        Text(gutter.first, color = gutter.second, fontSize = KeryxType.caption, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(14.dp))
                        Text(body.ifEmpty { " " }, color = ink, fontSize = KeryxType.caption, fontFamily = FontFamily.Monospace, softWrap = false)
                    }
                }
            }
        }
        if (d.lines.size > DIFF_FOLD + 5) {
            Text(
                if (expanded) "Show less" else "Show all ${d.lines.size} lines",
                color = accent, fontSize = KeryxType.micro, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(start = 6.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

// --- timeline ----------------------------------------------------------------------------------

@Composable
private fun TimelineBlock(t: RichBlock.Timeline, textColor: Color, modifier: Modifier) {
    val accent = keryxAccentInk()
    val rail = textColor.copy(alpha = 0.18f)
    val anyDone = t.items.any { it.done != null }
    Column(modifier = modifier.blockSurface(textColor).padding(horizontal = 12.dp, vertical = 10.dp)) {
        t.items.forEachIndexed { i, item ->
            val first = i == 0
            val last = i == t.items.lastIndex
            // With no checkboxes anywhere every stop is a plain milestone; with some, open ones are hollow.
            val filled = if (anyDone) item.done == true else true
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Canvas(Modifier.width(18.dp).fillMaxHeight()) {
                    val cx = size.width / 2f
                    val cy = 9.dp.toPx()
                    val r = 4.5.dp.toPx()
                    if (!first) drawLine(rail, Offset(cx, 0f), Offset(cx, cy - r), strokeWidth = 1.5.dp.toPx())
                    if (!last) drawLine(rail, Offset(cx, cy + r), Offset(cx, size.height), strokeWidth = 1.5.dp.toPx())
                    if (filled) drawCircle(accent, r, Offset(cx, cy))
                    else drawCircle(accent, r - 0.75.dp.toPx(), Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 10.dp)) {
                    item.time?.let {
                        Text(it, color = accent, fontSize = KeryxType.micro, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                    }
                    Text(
                        item.title,
                        color = if (item.done == true) textColor.copy(alpha = 0.7f) else textColor,
                        fontSize = KeryxType.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    item.detail?.let {
                        Text(it, color = textColor.copy(alpha = 0.62f), fontSize = KeryxType.caption)
                    }
                }
            }
        }
    }
}

// --- progress ----------------------------------------------------------------------------------

@Composable
private fun ProgressBlock(p: RichBlock.Progress, textColor: Color, modifier: Modifier) {
    val accent = keryxAccentInk()
    val good = KeryxStatus.good
    val drawIn = rememberDrawIn(p, durationMs = 900)
    Column(
        modifier = modifier.blockSurface(textColor).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        p.items.forEach { item ->
            Column(Modifier.semantics { contentDescription = "${item.label}: ${item.display}" }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.label, color = textColor, fontSize = KeryxType.body,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(item.display, color = textColor.copy(alpha = 0.6f), fontSize = KeryxType.micro, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(4.dp))
                val fill = if (item.fraction >= 1f) good else accent
                Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                    val r = CornerRadius(size.height / 2f, size.height / 2f)
                    drawRoundRect(textColor.copy(alpha = 0.10f), cornerRadius = r)
                    val w = size.width * item.fraction * drawIn
                    if (w > 0f) drawRoundRect(
                        Brush.horizontalGradient(listOf(fill.copy(alpha = 0.75f), fill)),
                        size = Size(w.coerceAtLeast(size.height), size.height),
                        cornerRadius = r,
                    )
                }
            }
        }
    }
}

// --- swatches ----------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwatchBlock(s: RichBlock.Swatches, textColor: Color, modifier: Modifier) {
    val clipboard = LocalClipboardManager.current
    var copiedHex by remember(s) { mutableStateOf<String?>(null) }
    LaunchedEffect(copiedHex) { if (copiedHex != null) { kotlinx.coroutines.delay(1400); copiedHex = null } }
    FlowRow(
        modifier = modifier.blockSurface(textColor).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        s.colors.forEach { sw ->
            val color = Color(sw.argb.toInt())
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(64.dp)
                    .clip(RoundedCornerShape(KeryxRadius.chip))
                    .clickable {
                        clipboard.setText(AnnotatedString(sw.hex))
                        copiedHex = sw.hex
                    }
                    .semantics { contentDescription = listOfNotNull(sw.name, sw.hex).joinToString(" ") }
                    .padding(2.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(KeryxRadius.field))
                        .background(color)
                        .border(1.dp, textColor.copy(alpha = 0.14f), RoundedCornerShape(KeryxRadius.field)),
                ) {
                    if (copiedHex == sw.hex) {
                        val ink = if (color.luminance() > 0.5f || color.alpha < 0.4f) Color.Black else Color.White
                        Text("✓", color = ink, fontSize = KeryxType.title, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(4.dp))
                sw.name?.let {
                    Text(it, color = textColor.copy(alpha = 0.85f), fontSize = KeryxType.micro, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(sw.hex, color = textColor.copy(alpha = 0.55f), fontSize = KeryxType.micro, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
        }
    }
}

// --- card --------------------------------------------------------------------------------------

@Composable
private fun CardBlock(
    card: RichBlock.Card, textColor: Color, renderMarkdown: @Composable (String) -> Unit, modifier: Modifier,
) {
    val uri = LocalUriHandler.current
    val accent = keryxAccentInk()
    Column(
        modifier = modifier
            .blockSurface(textColor)
            .border(1.dp, textColor.copy(alpha = 0.08f), BlockShape)
            .then(card.url?.let { u -> Modifier.clickable { runCatching { uri.openUri(u) } } } ?: Modifier),
    ) {
        card.image?.let { url ->
            coil3.compose.AsyncImage(
                model = url,
                contentDescription = card.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(148.dp).background(textColor.copy(alpha = 0.05f)),
            )
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(card.title, color = textColor, fontSize = KeryxType.title, fontWeight = FontWeight.SemiBold)
            card.subtitle?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, color = textColor.copy(alpha = 0.6f), fontSize = KeryxType.caption)
            }
            if (card.fields.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                card.fields.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(k, color = textColor.copy(alpha = 0.55f), fontSize = KeryxType.caption, modifier = Modifier.weight(0.38f))
                        Text(v, color = textColor, fontSize = KeryxType.caption, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.62f))
                    }
                }
            }
            card.body?.let {
                Spacer(Modifier.height(8.dp))
                renderMarkdown(it)
            }
            card.url?.let { link ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "↗ " + link.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/'),
                    color = accent, fontSize = KeryxType.micro, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// --- details -----------------------------------------------------------------------------------

@Composable
private fun DetailsBlock(
    d: RichBlock.Details, textColor: Color, renderMarkdown: @Composable (String) -> Unit, modifier: Modifier,
) {
    var open by remember(d) { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 90f else 0f, KeryxMotion.glide, label = "detailsChevron")
    Column(modifier = modifier.blockSurface(textColor)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text("▸", color = textColor.copy(alpha = 0.6f), fontSize = KeryxType.body, modifier = Modifier.rotate(turn))
            Spacer(Modifier.width(8.dp))
            Text(d.title, color = textColor, fontSize = KeryxType.body, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        AnimatedVisibility(visible = open, enter = keryxReveal(), exit = keryxConceal()) {
            Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                renderMarkdown(d.body)
            }
        }
    }
}
