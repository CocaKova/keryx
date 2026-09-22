package chat.keryx.app.presentation.artifact

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.presentation.ui.components.KeryxGlyphs
import chat.keryx.app.presentation.ui.components.KeryxRadius
import chat.keryx.core.model.Artifacts

/**
 * A page in the transcript (2.13): the glyph, the file's name, and the one verb. Replaces the
 * generic file chip for a `MEDIA:<path>.html` message, because "open with" for a mockup meant a
 * chooser with no good answer on it; the viewer is the answer. Drawn in the bubble's own ink so
 * it sits inside the bubble like the image cards do, not on top of it.
 */
@Composable
fun ArtifactCard(
    name: String,
    textColor: Color,
    busy: Boolean = false,
    onOpen: () -> Unit,
) {
    val shape = RoundedCornerShape(KeryxRadius.card)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clip(shape)
            .background(textColor.copy(alpha = 0.08f))
            .border(1.dp, textColor.copy(alpha = 0.14f), shape)
            .clickable(enabled = !busy, onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(
            KeryxGlyphs.FileClip,
            contentDescription = null,
            tint = textColor.copy(alpha = if (busy) 0.4f else 0.9f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                text = name,
                color = textColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = if (busy) "Opening…" else "Page · tap to open",
                color = textColor.copy(alpha = 0.6f),
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Open",
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(KeryxRadius.chip))
                .background(textColor.copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * The trailing chips under a reply that names a page by path ("saved it to
 * `/home/sy/out/mock-v1.html`") — one "Open <name>" per distinct path (2.13). Direct door only,
 * because only there can a bare path be fetched; on the Matrix door the sentence stays a
 * sentence. The scan is remembered per body and short-circuits on text with no `.htm` in it,
 * so a plain reply pays one `contains`.
 */
@Composable
fun ArtifactPathChips(
    content: String,
    roomId: String,
    textColor: Color,
) {
    val opener = LocalArtifactOpener.current
    if (opener?.canReadPaths != true) return
    val paths = remember(content) { Artifacts.findArtifactPaths(content) }
    if (paths.isEmpty()) return
    Column(Modifier.padding(top = 8.dp)) {
        paths.forEachIndexed { i, path ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            val name = Artifacts.nameOf(path)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(KeryxRadius.chip))
                    .background(textColor.copy(alpha = 0.10f))
                    .clickable { opener.open(ArtifactRef(path = path, name = name, roomId = roomId)) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Icon(
                    KeryxGlyphs.FileClip,
                    contentDescription = null,
                    tint = textColor.copy(alpha = 0.85f),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Open $name",
                    color = textColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 240.dp),
                )
            }
        }
    }
}
