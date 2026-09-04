package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.core.model.BotProfile
import chat.keryx.core.model.BotRoster

/**
 * The `@` completion row for Bot Mode (2.8): while the word under the caret starts with
 * `@`, the bots whose tags start with what has been typed line up as chips; a tap replaces
 * the partial tag with `@handle ` and moves the caret past it. The bot whose chat this is
 * stays out of the list — it cannot message itself. Nothing shows for a word that is not a
 * tag, so the row costs nothing in ordinary typing.
 */
@Composable
fun MentionChips(
    text: String,
    cursor: Int,
    bots: List<BotProfile>,
    self: String?,
    onPick: (String, Int) -> Unit,
) {
    val token = remember(text, cursor) { MentionToken.at(text, cursor) } ?: return
    val hits = remember(token, bots, self) {
        val q = token.typed.lowercase()
        bots.filter { it.name != self && it.tags.any { t -> t.startsWith(q) } }.take(6)
    }
    if (hits.isEmpty()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        hits.forEach { bot ->
            val light = botLightFor(bot.name, bot.label, bot.isDefault)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // 5dp round a 12sp handle is a 24dp target on a chip rail you tap mid-typing,
                // with the keyboard up and the row 6dp from its neighbour.
                modifier = Modifier
                    .padding(end = 6.dp)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(KeryxRadius.chip))
                    .background(light.accent.copy(alpha = 0.14f))
                    .clickable {
                        val handle = "@" + bot.handle + " "
                        val replaced = text.substring(0, token.start) + handle + text.substring(token.end)
                        onPick(replaced, token.start + handle.length)
                    }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            ) {
                HeraldSigil(light, fontSize = 11.sp)
                Spacer(Modifier.width(5.dp))
                // The herald palette clears AA at FULL strength on bare paper; a 14% wash of
                // the same hue underneath lifts the ground and drops every bot to 4.05–4.11:1.
                Text("@" + bot.handle, fontSize = 12.sp, color = keryxAccentInk(light.accent), fontWeight = FontWeight.Medium)
                if (bot.label != BotRoster.pretty(bot.name) && bot.label != bot.handle) {
                    Spacer(Modifier.width(5.dp))
                    Text(bot.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** The `@word` under the caret: its span in the text and what has been typed after the `@`. */
data class MentionToken(val start: Int, val end: Int, val typed: String) {
    companion object {
        fun at(text: String, cursor: Int): MentionToken? {
            val c = cursor.coerceIn(0, text.length)
            var start = c
            while (start > 0 && !text[start - 1].isWhitespace()) start--
            if (start >= text.length || text[start] != '@') return null
            if (start > 0 && !text[start - 1].isWhitespace()) return null
            var end = c
            while (end < text.length && !text[end].isWhitespace()) end++
            val typed = text.substring(start + 1, end)
            // An email address is not a tag; neither is a finished word with punctuation.
            if (typed.any { !(it.isLetterOrDigit() || it == '-' || it == '_') }) return null
            return MentionToken(start, end, typed)
        }
    }
}
