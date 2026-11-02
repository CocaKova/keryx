package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.keryx.app.R
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.transport.direct.UsageNumbers
import chat.keryx.app.transport.direct.UsageReport
import java.time.LocalDate

// Usage (makeover): what the agent's work cost — tokens, money, sessions — over the dashboard's
// own windows, from its Analytics route (`GET /api/analytics/usage`). Three numbers, the days as
// bars, then the models that spent it.

/** The usage spoke's second line: the window's tokens and sessions, once read. */
@Composable
internal fun usageSubtitle(viewModel: ChatViewModel): String {
    val usage by viewModel.agent.usage.collectAsState()
    val days by viewModel.agent.usageDays.collectAsState()
    val t = usage.data?.totals ?: return stringResource(R.string.mk_usage_subtitle)
    return stringResource(
        R.string.mk_usage_subtitle_read,
        usageCount(t.tokens),
        pluralStringResource(R.plurals.mk_usage_sessions, t.sessions.toInt(), t.sessions.toInt()),
        days,
    )
}

/** The row's end: what the window cost, when anything did; otherwise nothing. */
@Composable
internal fun usageTrailing(viewModel: ChatViewModel): String? {
    val usage by viewModel.agent.usage.collectAsState()
    return usage.data?.totals?.takeIf { it.cost > 0.0 }?.let { money(it.cost) }
}

private val WINDOWS = listOf(7, 30, 90)

@Composable
internal fun UsageTab(viewModel: ChatViewModel) {
    val panel by viewModel.agent.usage.collectAsState()
    val days by viewModel.agent.usageDays.collectAsState()
    val windowLabels = WINDOWS.map { it.toString() to pluralStringResource(R.plurals.mk_usage_days, it, it) }
    Column(Modifier.fillMaxSize()) {
        KeryxSegmented(
            options = windowLabels,
            selected = days.toString(),
            onSelect = { viewModel.agent.setUsageDays(it.toInt()) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        PanelErrorLine(panel.error)
        val report = panel.data
        if (report == null) {
            if (panel.error == null) PanelLoading()
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            item(key = "usage-tiles") { UsageTiles(report.totals) }
            if (report.totals.tokens == 0L && report.totals.sessions == 0L) item(key = "usage-none") {
                Text(
                    stringResource(R.string.mk_usage_none, report.days),
                    fontSize = KeryxType.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 20.dp),
                )
            } else {
                item(key = "usage-days") { UsageDays(report) }
                if (report.byModel.isNotEmpty()) item(key = "usage-models") {
                    KeryxHubGroup(stringResource(R.string.mk_usage_by_model), modifier = Modifier.animateContentSize(KeryxMotion.settleSize)) {
                        report.byModel.forEachIndexed { i, (model, n) ->
                            if (i > 0) KeryxHubDivider()
                            UsageModelRow(model, n)
                        }
                    }
                }
                item(key = "usage-note") {
                    Text(
                        stringResource(
                            if (report.totals.costIsEstimate) R.string.mk_usage_note_estimate else R.string.mk_usage_note_reported,
                        ),
                        fontSize = KeryxType.micro,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp),
                    )
                }
            }
        }
    }
}

/** Three hero numbers: tokens (in and out under it), cost, sessions. */
@Composable
private fun UsageTiles(t: UsageNumbers) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        UsageTile(
            value = usageCount(t.tokens),
            label = stringResource(R.string.mk_usage_tokens),
            detail = stringResource(R.string.mk_usage_in_out, usageCount(t.input), usageCount(t.output)),
            modifier = Modifier.weight(1f),
        )
        UsageTile(
            value = money(t.cost),
            label = stringResource(if (t.costIsEstimate) R.string.mk_usage_cost_estimated else R.string.mk_usage_cost),
            detail = null,
            modifier = Modifier.weight(1f),
        )
        UsageTile(
            value = t.sessions.toString(),
            label = pluralStringResource(R.plurals.mk_usage_sessions_label, t.sessions.toInt()),
            detail = pluralStringResource(R.plurals.mk_usage_calls, t.apiCalls.toInt(), usageCount(t.apiCalls)),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun UsageTile(value: String, label: String, detail: String?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(KeryxRadius.sheet)
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f), shape)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(value, fontSize = KeryxType.display, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(label, fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        if (detail != null) Text(
            detail,
            fontSize = KeryxType.micro,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * The window's days as thin bars of tokens, one hue, a quiet day a gap rather than a missing
 * column — the gateway lists only days that had a session, so the calendar is filled in here.
 * The busiest day is named under the bars; TalkBack reads the same line.
 */
@Composable
private fun UsageDays(report: UsageReport) {
    val byDay = remember(report) { report.daily.toMap() }
    val days = remember(report) {
        val today = LocalDate.now()
        (report.days - 1 downTo 0).map { today.minusDays(it.toLong()).toString() }
    }
    val values = remember(days, byDay) { days.map { byDay[it]?.tokens ?: 0L } }
    val peak = values.maxOrNull()?.takeIf { it > 0 } ?: return
    val peakDay = days[values.indexOf(peak)]
    val bar = MaterialTheme.colorScheme.primary
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val summary = stringResource(R.string.mk_usage_peak, usageCount(peak), shortDay(peakDay))
    KeryxHubGroup(stringResource(R.string.mk_usage_by_day)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .semantics { contentDescription = summary },
            ) {
                val n = values.size
                val gap = 2.dp.toPx()
                val w = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
                val r = (w / 2f).coerceAtMost(4.dp.toPx())
                values.forEachIndexed { i, v ->
                    if (v <= 0L) return@forEachIndexed
                    val h = (size.height * v / peak).coerceAtLeast(2.dp.toPx())
                    drawRoundRect(
                        bar,
                        topLeft = Offset(i * (w + gap), size.height - h),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(r, r),
                    )
                }
                drawRect(base, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(shortDay(days.first()), fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(summary, fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text(shortDay(days.last()), fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun UsageModelRow(model: String, n: UsageNumbers) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                model, fontSize = KeryxType.title, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.mk_usage_in_out, usageCount(n.input), usageCount(n.output)) + " · " +
                    pluralStringResource(R.plurals.mk_usage_sessions, n.sessions.toInt(), n.sessions.toInt()),
                fontSize = KeryxType.caption,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (n.cost > 0.0) money(n.cost) else usageCount(n.tokens),
            fontSize = KeryxType.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 950 · 12.4K · 3.1M · 1.2B — a count you read at a glance. */
internal fun usageCount(n: Long): String = when {
    n < 1_000L -> n.toString()
    n < 1_000_000L -> trim1(n / 1_000.0) + "K"
    n < 1_000_000_000L -> trim1(n / 1_000_000.0) + "M"
    else -> trim1(n / 1_000_000_000.0) + "B"
}

private fun trim1(v: Double): String =
    if (v >= 100) v.toLong().toString()
    else String.format(java.util.Locale.ROOT, "%.1f", v).removeSuffix(".0")

/** USD as the dashboard shows it: cents, and "<$0.01" for a sliver rather than "$0.00". */
internal fun money(usd: Double): String = when {
    usd <= 0.0 -> "$0"
    usd < 0.01 -> "<$0.01"
    else -> String.format(java.util.Locale.ROOT, "$%.2f", usd)
}

/** "2026-11-02" → "Nov 2", in the phone's language. */
private fun shortDay(iso: String): String = runCatching {
    LocalDate.parse(iso).format(
        java.time.format.DateTimeFormatter.ofPattern(
            android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "MMMd"),
            java.util.Locale.getDefault(),
        ),
    )

}.getOrDefault(iso)
