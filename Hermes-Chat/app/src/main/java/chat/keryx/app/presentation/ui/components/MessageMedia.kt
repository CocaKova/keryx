package chat.keryx.app.presentation.ui.components

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.input.pointer.pointerInput
import chat.keryx.app.util.mediaMimeFor
import chat.keryx.app.util.saveMediaToDevice
import chat.keryx.app.util.shareMedia
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import chat.keryx.core.model.ImageFormat
import chat.keryx.core.model.MediaKind

/**
 * Process-wide cache of decoded bitmaps keyed by media id / mxc URL. The chat list re-enters
 * composition often, so without this the same image re-downloads/re-decodes and flickers between the
 * placeholder and the picture. Sized by BYTES, not count: 40 full-resolution photos (the old
 * count-bound) could dwarf the whole heap budget. Registered with CacheRegistry so memory
 * pressure sheds decoded pixels first — they re-decode from the byte cache on demand.
 */
object KeryxBitmapCache {
    private val cache = object : android.util.LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(48L shl 20).toInt(),
    ) {
        override fun sizeOf(key: String, value: ImageBitmap) =
            value.asAndroidBitmap().allocationByteCount
    }

    init {
        chat.keryx.app.util.CacheRegistry.register { aggressive ->
            if (aggressive) cache.evictAll() else cache.trimToSize(cache.maxSize() / 2)
        }
    }

    fun get(key: String): ImageBitmap? = cache.get(key)
    fun put(key: String, bitmap: ImageBitmap) { cache.put(key, bitmap) }
}

/**
 * Decode [bytes] sampled down toward [targetPx] — the two-pass bounds-then-inSampleSize pattern.
 * The display path used to decode at source resolution for a ≤260×320dp bubble: one 12 MP photo
 * is ~48 MB of ARGB the bubble then scales away. [longEdge] picks which edge honors the target:
 * true for Fit-rendered bubbles (the long edge is what the layout bounds), false when the SHORT
 * edge must stay sharp (fullscreen zoom, cropped square thumbs) — a 1080×12000 terminal
 * screenshot must keep its width readable under pinch-zoom even though its long edge is huge.
 */
fun decodeSampled(bytes: ByteArray, targetPx: Int, longEdge: Boolean = true): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val edge =
        if (longEdge) maxOf(bounds.outWidth, bounds.outHeight)
        else minOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (edge / (sample * 2) >= targetPx) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}.getOrNull()

/**
 * Whether a moving picture can be played here, and whether these bytes are one.
 *
 * Two separate questions, deliberately answered together at the one call site that cares.
 * [ImageFormat] reads the header; ImageDecoder — the platform's only animator — arrives at
 * API 28, two releases above minSdk. Below that a GIF is not lost: it falls through to the
 * still path, where BitmapFactory hands back frame one. A frozen picture is a poor showing
 * of a reaction GIF, but it is the right picture, and it is not a hole in the transcript.
 */
private object AnimatedImageSupport {

    fun canPlay(bytes: ByteArray): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && ImageFormat.mayAnimate(bytes)

    /** Decode to a playable drawable, or null if the header promised more than the file had. */
    @RequiresApi(Build.VERSION_CODES.P)
    fun decode(bytes: ByteArray): Drawable? = runCatching {
        ImageDecoder.decodeDrawable(
            ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)),
        ) { decoder, info, _ ->
            // The same pixel budget the still path keeps — and it matters more here, because
            // every frame pays it, not one.
            val edge = maxOf(info.size.width, info.size.height)
            var sample = 1
            while (edge / (sample * 2) >= 1_024) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
    }.getOrNull()
}

/**
 * A picture that moves: GIF and animated WebP.
 *
 * Rendered through an ImageView rather than a Compose painter because Compose has no animated
 * image type, and AnimatedImageDrawable already owns frame timing, the file's own loop count,
 * and its own invalidation — reimplementing that in a painter would be a worse clock.
 *
 * Nothing is cached here, and that is the point: a decoded animation is every frame of itself,
 * so it is decoded per call site and released when the bubble leaves. The BYTES behind it are
 * cached (ChatViewModel.mediaBytesCache), so scrolling back re-decodes without re-downloading.
 *
 * It plays under reduced motion. Everywhere else in Keryx motion is chrome and yields; here the
 * motion IS the message the agent chose to send, and a reaction GIF stopped on its first frame
 * is a broken image, not a calmer one.
 *
 * [constrainAspect] lets the bubble bound its height by the file's own ratio; the fullscreen
 * viewer turns it off and lets FIT_CENTER letterbox inside the whole screen.
 */
@Composable
private fun AnimatedImage(
    bytes: ByteArray,
    contentDescription: String,
    textColor: Color,
    modifier: Modifier = Modifier,
    constrainAspect: Boolean = true,
) {
    var drawable by remember(bytes) { mutableStateOf<Drawable?>(null) }
    var frame by remember(bytes) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bytes) {
        withContext(Dispatchers.IO) {
            // Decoding a whole animation is not a main-thread errand even at this sample size.
            val d = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AnimatedImageSupport.decode(bytes) else null
            // The header promised motion and the decoder disagreed. Show the picture standing
            // still rather than an empty bubble — the caller has already ruled out its own path.
            if (d != null) drawable = d else frame = decodeSampled(bytes, targetPx = 1_024)
        }
    }

    val moving = drawable
    val still = frame
    when {
        moving != null -> {
            DisposableEffect(moving) {
                (moving as? AnimatedImageDrawable)?.start()
                onDispose { (moving as? AnimatedImageDrawable)?.stop() }
            }
            val ratio = remember(moving) {
                val w = moving.intrinsicWidth.toFloat()
                val h = moving.intrinsicHeight.toFloat()
                if (w > 0f && h > 0f) w / h else 1f
            }
            AndroidView(
                factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                update = { it.setImageDrawable(moving) },
                modifier = if (constrainAspect) modifier.aspectRatio(ratio) else modifier,
            )
        }
        still != null -> Image(
            bitmap = still,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
        else -> Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 200.dp, height = 140.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(textColor.copy(alpha = 0.08f)),
        ) {
            Text("\uD83C\uDFAC  loading\u2026", color = textColor.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
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
        // Set only for a picture that can move. A GIF cannot live in KeryxBitmapCache — that
        // cache holds one decoded frame, and one frame is precisely what a GIF is not — so the
        // source bytes are held instead. Animated files ONLY: keeping every photo's source bytes
        // alive in composition would undo the whole sampled-decode budget below.
        var moving by remember(loadKey) { mutableStateOf<ByteArray?>(null) }
        val bitmap by produceState<ImageBitmap?>(initialValue = cached, loadKey) {
            if (cached != null) return@produceState
            val bytes = loader() ?: return@produceState
            if (AnimatedImageSupport.canPlay(bytes)) { moving = bytes; return@produceState }
            // Bubble-sized decode: the render box is ≤260×320dp, so ~1024px on the long edge
            // covers the densest screens; full-resolution pixels only exist for fullscreen.
            value = decodeSampled(bytes, targetPx = 1_024)?.also { KeryxBitmapCache.put(loadKey, it) }
        }
        val bmp = bitmap
        val animated = moving
        var showFullscreen by androidx.compose.runtime.remember { mutableStateOf(false) }
        if (animated != null) {
            AnimatedImage(
                bytes = animated,
                contentDescription = fileName.ifBlank { "animated image" },
                textColor = textColor,
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showFullscreen = true },
            )
            if (showFullscreen) {
                // No sharper second decode: unlike a still, the fullscreen copy is the same
                // file at the same sample budget — every frame pays for resolution here.
                FullscreenImageViewer(
                    image = null,
                    animated = animated,
                    fileName = fileName.ifBlank { "animated image" },
                    bytesProvider = loader,
                ) { showFullscreen = false }
            }
        } else if (bmp != null) {
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
                // Fullscreen gets its own sharper decode (short edge ≈ screen width, uncached,
                // transient) so pinch-zoom doesn't inherit the bubble's downsample; the bubble
                // bitmap shows until it lands.
                val fullRes by produceState<ImageBitmap?>(initialValue = null, loadKey) {
                    value = loader()?.let { decodeSampled(it, targetPx = 1_080, longEdge = false) }
                }
                FullscreenImageViewer(
                    image = fullRes ?: bmp,
                    fileName = fileName.ifBlank { "image" },
                    bytesProvider = loader,
                ) { showFullscreen = false }
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
    } else if (kind == MediaKind.VIDEO) {
        InlineVideo(loadKey, fileName, textColor, loader)
    } else {
        // Audio / files: tap to download (once) and hand off to the system viewer/player;
        // long-press saves a copy into Music/Download so it outlives the cache.
        val context = androidx.compose.ui.platform.LocalContext.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var opening by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        FileChip(
            kind, fileName.ifBlank { "attachment" }, textColor, opening,
            onLongClick = {
                if (opening) return@FileChip
                opening = true
                scope.launch {
                    val bytes = loader()
                    val where = bytes?.let { saveMediaToDevice(context, it, fileName.ifBlank { "file" }, kind) }
                    android.widget.Toast.makeText(
                        context,
                        if (where != null) "Saved to $where" else "Couldn't save",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    opening = false
                }
            },
        ) {
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

/** A downloaded, file-backed video ready to render: poster frame + duration for the card. */
private data class ReadyVideo(val file: java.io.File, val poster: ImageBitmap?, val durationMs: Long)

/**
 * Built-in video renderer: the bubble shows a poster card (first frame + ▶ + duration) and tapping
 * it plays the video in a fullscreen in-app ExoPlayer with transport controls — no more punting to
 * an external app. Bytes are fetched once and cached as a file (ExoPlayer needs a seekable source);
 * re-entering the chat reuses the file and the poster comes from [KeryxBitmapCache].
 */
@Composable
private fun InlineVideo(
    loadKey: String,
    fileName: String,
    textColor: Color,
    loader: suspend () -> ByteArray?,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ready by produceState<ReadyVideo?>(initialValue = null, loadKey) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(context.cacheDir, "media").apply { mkdirs() }
                val safeKey = loadKey.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val ext = fileName.substringAfterLast('.', "mp4").take(5).ifBlank { "mp4" }
                val file = java.io.File(dir, "video_$safeKey.$ext")
                if (!file.exists() || file.length() == 0L) {
                    val bytes = loader() ?: return@runCatching null
                    file.writeBytes(bytes)
                }
                var durationMs = 0L
                val poster = KeryxBitmapCache.get("$loadKey#vposter") ?: runCatching {
                    val mmr = android.media.MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(file.path)
                        durationMs = mmr.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                        )?.toLongOrNull() ?: 0L
                        mmr.frameAtTime?.asImageBitmap()
                            ?.also { KeryxBitmapCache.put("$loadKey#vposter", it) }
                    } finally { mmr.release() }
                }.getOrNull()
                ReadyVideo(file, poster, durationMs)
            }.getOrNull()
        }
    }

    var playing by remember { mutableStateOf(false) }
    val video = ready
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .widthIn(max = 260.dp)
            .heightIn(min = 120.dp, max = 180.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .then(if (video != null) Modifier.clickable { playing = true } else Modifier),
    ) {
        val poster = video?.poster
        if (poster != null) {
            Image(
                bitmap = poster,
                contentDescription = fileName.ifBlank { "video" },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (video == null) {
            Text("🎬  loading…", color = textColor.copy(alpha = 0.6f), fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 40.dp, vertical = 50.dp))
        } else {
            // Play affordance + duration badge over the poster.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(46.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
            ) {
                Text("▶", color = Color.White, fontSize = 18.sp)
            }
            if (video.durationMs > 0) {
                Text(
                    formatDuration(video.durationMs),
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
    if (playing && video != null) {
        FullscreenVideoPlayer(video.file, fileName.ifBlank { "video" }) { playing = false }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

/** Fullscreen in-app player: ExoPlayer + standard transport controls, save/share/✕ up top. */
@Composable
private fun FullscreenVideoPlayer(file: java.io.File, fileName: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val player = remember {
            androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
                setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(file)))
                prepare()
                playWhenReady = true
            }
        }
        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose { player.release() }
        }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply {
                        this.player = player
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            MediaActionBar(
                fileName = fileName,
                kind = MediaKind.VIDEO,
                bytesProvider = {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { file.readBytes() }.getOrNull()
                    }
                },
                onDismiss = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

/**
 * A full-screen image viewer: tap (or the ✕) to dismiss, pinch or double-tap to zoom, drag to
 * pan. Save/share act on the ORIGINAL bytes via [bytesProvider] — never a re-encoded bitmap —
 * so the file that lands in Pictures is exactly what was sent.
 */
@Composable
private fun FullscreenImageViewer(
    image: ImageBitmap?,
    fileName: String,
    bytesProvider: suspend () -> ByteArray?,
    animated: ByteArray? = null,
    onDismiss: () -> Unit,
) {
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
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = {
                            if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val zoom = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                )
                .transformable(transformState)
            // A moving picture keeps moving while you pinch it — the transform is a layer over
            // the view, so the drawable never learns it is being zoomed.
            if (animated != null) {
                AnimatedImage(
                    bytes = animated,
                    contentDescription = fileName,
                    textColor = Color.White,
                    constrainAspect = false,
                    modifier = zoom,
                )
            } else if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = fileName,
                    contentScale = ContentScale.Fit,
                    modifier = zoom,
                )
            }
            MediaActionBar(
                fileName = fileName,
                kind = MediaKind.IMAGE,
                bytesProvider = bytesProvider,
                onDismiss = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

/**
 * The viewer chrome shared by images and videos: ⬇ save, share, ✕ — on a scrim pill so the
 * icons survive light content behind them.
 */
@Composable
private fun MediaActionBar(
    fileName: String,
    kind: MediaKind,
    bytesProvider: suspend () -> ByteArray?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(12.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.45f)),
    ) {
        IconButton(onClick = {
            if (busy) return@IconButton
            busy = true
            scope.launch {
                val bytes = bytesProvider()
                val where = bytes?.let { saveMediaToDevice(context, it, fileName, kind) }
                android.widget.Toast.makeText(
                    context,
                    if (where != null) "Saved to $where" else "Couldn't save",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                busy = false
            }
        }) {
            Icon(
                androidx.compose.material.icons.Icons.Default.Download,
                contentDescription = "Save to device",
                tint = if (busy) Color.White.copy(alpha = 0.4f) else Color.White,
            )
        }
        IconButton(onClick = {
            if (busy) return@IconButton
            busy = true
            scope.launch {
                val bytes = bytesProvider()
                val ok = bytes != null && shareMedia(context, bytes, fileName, kind)
                if (!ok) android.widget.Toast.makeText(context, "Couldn't share", android.widget.Toast.LENGTH_SHORT).show()
                busy = false
            }
        }) {
            Icon(
                androidx.compose.material.icons.Icons.Default.Share,
                contentDescription = "Share",
                tint = if (busy) Color.White.copy(alpha = 0.4f) else Color.White,
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
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
        val mime = mediaMimeFor(fileName, kind)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Open with").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        android.widget.Toast.makeText(context, "No app can open this file", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FileChip(
    kind: MediaKind,
    label: String,
    textColor: Color,
    busy: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
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
            .then(
                if (onClick != null) Modifier.combinedClickable(
                    onClick = { onClick() },
                    onLongClick = onLongClick,
                ) else Modifier
            )
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
