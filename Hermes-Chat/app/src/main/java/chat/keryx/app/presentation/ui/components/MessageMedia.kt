package chat.keryx.app.presentation.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import chat.keryx.app.domain.model.MediaKind

/**
 * Tiny process-wide cache of decoded bitmaps keyed by media id / mxc URL. The chat list re-enters
 * composition often, so without this the same image re-downloads/re-decodes and flickers between the
 * placeholder and the picture. Keeps the most-recent images steady and instant on return.
 */
object KeryxBitmapCache {
    private val cache = android.util.LruCache<String, ImageBitmap>(40)
    fun get(key: String): ImageBitmap? = cache.get(key)
    fun put(key: String, bitmap: ImageBitmap) { cache.put(key, bitmap) }
}

/**
 * Renders a media message: images inline, everything else as a labeled chip.
 * [loadKey] uniquely identifies the media (event id) so the bytes are fetched once;
 * [loader] resolves the bytes (handles plaintext and E2EE-encrypted media in the repository).
 */
@Composable
fun MessageMedia(
    loadKey: String,
    kind: MediaKind,
    fileName: String,
    textColor: Color,
    loader: suspend () -> ByteArray?,
) {
    if (kind == MediaKind.IMAGE) {
        // Seed from cache so a re-entered bubble shows the image immediately (no placeholder flash).
        val cached = remember(loadKey) { KeryxBitmapCache.get(loadKey) }
        val bitmap by produceState<ImageBitmap?>(initialValue = cached, loadKey) {
            if (cached != null) return@produceState
            value = loader()?.let { bytes ->
                val decoded = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
                if (decoded != null) KeryxBitmapCache.put(loadKey, decoded)
                decoded
            }
        }
        val bmp = bitmap
        var showFullscreen by androidx.compose.runtime.remember { mutableStateOf(false) }
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = fileName.ifBlank { "image" },
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showFullscreen = true },
            )
            if (showFullscreen) {
                FullscreenImageViewer(bmp, fileName.ifBlank { "image" }) { showFullscreen = false }
            }
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 200.dp, height = 140.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(textColor.copy(alpha = 0.08f)),
            ) {
                Text("🖼  loading…", color = textColor.copy(alpha = 0.6f), fontSize = 12.sp)
            }
        }
    } else {
        // Videos / audio / files: tap to download (once) and hand off to the system viewer/player.
        val context = androidx.compose.ui.platform.LocalContext.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var opening by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        FileChip(kind, fileName.ifBlank { "attachment" }, textColor, opening) {
            if (opening) return@FileChip
            opening = true
            scope.launch {
                val bytes = loader()
                if (bytes != null) openExternally(context, bytes, fileName.ifBlank { "file" }, kind)
                else android.widget.Toast.makeText(context, "Couldn't load attachment", android.widget.Toast.LENGTH_SHORT).show()
                opening = false
            }
        }
    }
}

/**
 * A full-screen image viewer: tap (or the ✕) to dismiss, pinch to zoom, drag to pan. Reuses the
 * already-decoded bitmap so it opens instantly.
 */
@Composable
private fun FullscreenImageViewer(image: ImageBitmap, fileName: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by androidx.compose.runtime.remember { mutableStateOf(1f) }
        var offset by androidx.compose.runtime.remember { mutableStateOf(Offset.Zero) }
        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 6f)
            offset = if (scale <= 1f) Offset.Zero else offset + panChange
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
                .clickable(
                    indication = null,
                    interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                ) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image,
                contentDescription = fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    )
                    .transformable(transformState),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

private suspend fun openExternally(
    context: android.content.Context,
    bytes: ByteArray,
    fileName: String,
    kind: MediaKind,
) {
    runCatching {
        val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val dir = java.io.File(context.cacheDir, "media").apply { mkdirs() }
            val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file" }
            java.io.File(dir, safe).apply { writeBytes(bytes) }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mime = mimeFor(fileName, kind)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Open with").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        android.widget.Toast.makeText(context, "No app can open this file", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun mimeFor(fileName: String, kind: MediaKind): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    val fromExt = if (ext.isNotBlank())
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) else null
    return fromExt ?: when (kind) {
        MediaKind.VIDEO -> "video/*"
        MediaKind.AUDIO -> "audio/*"
        MediaKind.IMAGE -> "image/*"
        MediaKind.FILE -> "*/*"
    }
}

@Composable
private fun FileChip(kind: MediaKind, label: String, textColor: Color, busy: Boolean = false, onClick: (() -> Unit)? = null) {
    val icon = when (kind) {
        MediaKind.AUDIO -> "🎵"
        MediaKind.VIDEO -> "🎬"
        MediaKind.IMAGE -> "🖼"
        MediaKind.FILE -> "📎"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(textColor.copy(alpha = 0.08f))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(if (busy) "⏳" else icon, fontSize = 18.sp)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp),
        )
    }
}
