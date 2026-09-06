package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.core.model.ContextBreakdown

/**
 * What is in the window (2.10). The ring says how full; this says of what — system prompt,
 * tools, skills, memory, the conversation — as one bar and a list, the Desktop's Context Usage
 * popover as a sheet. Fetched on open, never polled: a number you asked to see, not a meter.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ContextBreakdownSheet(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    var data by remember { mutableStateOf<ContextBreakdown?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val r = viewModel.contextBreakdown()
        if (r == null) error = "Only the direct door can itemise the window."
        else r.onSuccess { data = it }.onFailure { error = it.message ?: "Couldn't read the window" }
    }
    KeryxSheet(onDismiss = onDismiss, title = "Context") {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            val d = data
            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                d == null -> PanelLoading()
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${d.percent}%",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "${compact(d.used)} of ${compact(d.max)} tokens",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (d.model.isNotBlank()) Text(
                                d.model, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    // One bar, the categories laid end to end in the gateway's order; each a
                    // step between the two accents so the legend and the bar share a hue.
                    val accent = MaterialTheme.colorScheme.primary
                    val accent2 = MaterialTheme.colorScheme.tertiary
                    val n = d.categories.size.coerceAtLeast(1)
                    fun hue(i: Int): Color = lerp(accent, accent2, if (n == 1) 0f else i.toFloat() / (n - 1))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
                    ) {
                        d.categories.forEachIndexed { i, c ->
                            val w = (c.tokens.toFloat() / d.total).coerceAtLeast(0.004f)
                            Box(Modifier.weight(w).height(10.dp).background(hue(i)))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    d.categories.forEachIndexed { i, c ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Box(Modifier.size(9.dp).clip(CircleShape).background(hue(i)))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                c.label, fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                compact(c.tokens), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "${(100f * c.tokens / d.total).let { if (it < 1f) "<1" else it.toInt().toString() }}%",
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.width(38.dp),
                            )
                        }
                    }
                    if (d.categories.isEmpty()) Text(
                        "The gateway has no live agent for this session yet — send something first.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

private fun compact(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000f)
    n >= 10_000 -> "${n / 1000}k"
    n >= 1_000 -> String.format("%.1fk", n / 1000f)
    else -> n.toString()
}
