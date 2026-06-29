package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Ethereal context citations: a row of glowing numbered chips (the ⁽ⁿ⁾ superscripts in the answer
 * point here). Tap a chip to reveal where Hermes pulled that fact from (memory, file, web …). Only
 * shown when the bundled `keryx` plugin emits citation markers — absent ⇒ this never renders.
 */
@Composable
fun CitationsBar(items: List<MessageParser.Citation>, baseColor: Color) {
    val accent = MaterialTheme.colorScheme.primary
    var selected by remember { mutableStateOf<Int?>(null) }
    Column(modifier = Modifier.padding(top = 7.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            Text("Sources", color = accent.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
            items.forEach { c ->
                val sel = selected == c.n
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(accent.copy(alpha = if (sel) 0.55f else 0.24f), accent.copy(alpha = 0.08f))
                            )
                        )
                        .border(1.dp, accent.copy(alpha = if (sel) 0.6f else 0.3f), CircleShape)
                        .clickable { selected = if (sel) null else c.n }
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                ) {
                    Text("${c.n}", color = baseColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        val current = items.firstOrNull { it.n == selected }
        AnimatedVisibility(
            visible = current != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            current?.let { c ->
                Column(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.07f))
                        .border(1.dp, accent.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(kindEmoji(c.kind), fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            c.kind.ifBlank { "source" },
                            color = accent.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (c.label.isNotBlank()) {
                        Spacer(modifier = Modifier.padding(top = 3.dp))
                        Text(c.label, color = baseColor, fontSize = 13.sp)
                    }
                    if (c.detail.isNotBlank()) {
                        Spacer(modifier = Modifier.padding(top = 2.dp))
                        Text(c.detail, color = baseColor.copy(alpha = 0.6f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

private fun kindEmoji(kind: String) = when (kind.lowercase()) {
    "memory", "graphiti", "recall" -> "🧠"
    "file", "doc", "document" -> "📄"
    "web", "url", "search" -> "🌐"
    "session", "chat", "conversation" -> "💬"
    else -> "🔗"
}

/**
 * "Skill Distilled" pill shown when SILAS closes its learning loop and saves a new procedural skill.
 * Tap to peek at what it learned. Full editing (the Skill Forge) lands once the keryx plugin's
 * read/write commands are wired; for now this surfaces the distillation so it isn't invisible.
 */
@Composable
fun SkillDistilledPill(skill: MessageParser.Segment.SkillDistilled, baseColor: Color) {
    val accent = MaterialTheme.colorScheme.primary
    var open by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(top = 7.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(
                    Brush.linearGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.08f)))
                )
                .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(percent = 50))
                .clickable { open = !open }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text("✦", fontSize = 12.sp)
            Spacer(modifier = Modifier.width(7.dp))
            Text("Skill Distilled", color = accent.copy(alpha = 0.92f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (skill.name.isNotBlank()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text("· ${skill.name}", color = baseColor, fontSize = 12.sp)
            }
        }
        AnimatedVisibility(
            visible = open,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.07f))
                    .border(1.dp, accent.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                    .padding(10.dp),
            ) {
                if (skill.summary.isNotBlank()) {
                    Text(skill.summary, color = baseColor.copy(alpha = 0.85f), fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.padding(top = 4.dp))
                Text(
                    "Editing this skill (the Skill Forge) unlocks when the keryx plugin is enabled.",
                    color = baseColor.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                )
            }
        }
    }
}
