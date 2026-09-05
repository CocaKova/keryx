package chat.keryx.app.presentation.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.util.saveMediaToDevice
import chat.keryx.app.util.shareMedia
import chat.keryx.core.model.MediaTags
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * The renderer's coil transformer, plus a tap target.
 *
 * 2.9.1 made a bare image URL in prose render AS the image, which cost the thing the link
 * used to give for free: the address itself, selectable and copyable. An image with no way
 * back to its URL is a worse deal than a link for anyone who wanted to send the GIF on. The
 * transformer is the seam where that can be repaired without touching the markdown renderer:
 * [ImageTransformer.transform] is handed the destination and returns an [ImageData] carrying
 * a Modifier, so the tap target rides along with the picture — inline in a paragraph or
 * standing alone, whichever shape the parser chose.
 *
 * Delegation, deliberately: `intrinsicSize` and `placeholderConfig` decide layout and the
 * loading placeholder, and coil's answers are the ones the bubbles were tuned against.
 */
class TappableImageTransformer(
    private val onTap: (String) -> Unit,
) : ImageTransformer by Coil3ImageTransformerImpl {

    @Composable
    override fun transform(link: String): ImageData? {
        val data = Coil3ImageTransformerImpl.transform(link) ?: return null
        return data.copy(modifier = data.modifier.then(Modifier.clickable { onTap(link) }))
    }
}

/** Fetch an image's bytes for share/save. Capped: a bubble tap must not pull a huge file. */
private const val MAX_IMAGE_BYTES = 64 * 1024 * 1024

private suspend fun fetchBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            conn.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_IMAGE_BYTES) return@runCatching null
                }
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}

private fun toast(context: Context, msg: String) =
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

/**
 * What you can do with an image you tapped. Copy and Open need nothing but the URL; Share and
 * Save need the file, so they fetch it and say so if the fetch fails rather than failing mute.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun InlineImageSheet(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var busy by remember { mutableStateOf(false) }
    val name = remember(url) { MediaTags.nameOf(url) }
    val kind = remember(url) { MediaTags.kindOf(url) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = url,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))

            SheetAction(KeryxGlyphs.Copy, "Copy link", enabled = !busy) {
                clipboard.setText(AnnotatedString(url))
                toast(context, "Link copied")
                onDismiss()
            }
            SheetAction(KeryxGlyphs.Share, if (busy) "Working…" else "Share", enabled = !busy) {
                busy = true
                scope.launch {
                    val bytes = fetchBytes(url)
                    busy = false
                    if (bytes == null || !shareMedia(context, bytes, name, kind)) {
                        toast(context, "Couldn't fetch the image — link copied instead")
                        clipboard.setText(AnnotatedString(url))
                    }
                    onDismiss()
                }
            }
            SheetAction(KeryxGlyphs.Download, if (busy) "Working…" else "Save to Photos", enabled = !busy) {
                busy = true
                scope.launch {
                    val bytes = fetchBytes(url)
                    val where = bytes?.let { saveMediaToDevice(context, it, name, kind) }
                    busy = false
                    toast(context, where?.let { "Saved to $it" } ?: "Couldn't save that image")
                    onDismiss()
                }
            }
            SheetAction(KeryxGlyphs.Exit, "Open in browser", enabled = !busy) {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure { toast(context, "Nothing here opens that link") }
                onDismiss()
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint =
        if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}
