package chat.keryx.app.presentation.artifact

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import chat.keryx.app.KeryxApp
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.presentation.ui.components.KeryxGlyphs
import chat.keryx.app.presentation.ui.components.KeryxRadius
import chat.keryx.app.presentation.ui.components.KeryxSpace
import chat.keryx.app.presentation.ui.components.openExternally
import chat.keryx.app.presentation.ui.nav.KeryxDest
import chat.keryx.app.transport.direct.GatewayRest
import chat.keryx.app.util.saveMediaToDevice
import chat.keryx.app.util.shareMedia
import chat.keryx.core.model.MediaKind
import kotlinx.coroutines.launch

/**
 * The artifact viewer (2.13): a page the agent wrote, drawn by a WebView inside a [KeryxSpace]
 * on the nav stack, so back returns to the chat exactly where you were.
 *
 * Two sources, tried in this order. Bytes the transport already carries — the media message a
 * `MEDIA:` line became, on either door — are asked of [ChatViewModel.loadMessageMedia], which
 * is the same cached download the card would have made. Failing that, a path on the gateway
 * host is read over the dashboard's `read-text` (direct door only); a file past the server's
 * preview cap comes down whole through the file download instead, since a mock cut off at
 * 512 KiB is a broken mock.
 *
 * The REST leg is built here from the same settings the transport dials with (the fleet's
 * active gateway, its token or native refresh pair), exactly as the gateway "Test" probe does.
 * The transport's own client is private to it, and a page read is ambient work, not a session's.
 *
 * The WebView is fenced: no file or content access, no JS bridge, and `loadDataWithBaseURL`
 * with a null base, so a relative fetch in the page has no origin to reach the phone through.
 * Network stays on, because a mock that pulls a font or a chart library off a CDN is the
 * common case. Links the page tries to navigate to go to the browser; the viewer stays on
 * the page. No algorithmic darkening: the page is the author's design, and a report the agent
 * set on cream should arrive on cream.
 */
@Composable
fun ArtifactSpace(
    dest: KeryxDest.Artifact,
    viewModel: ChatViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as? KeryxApp
    val scope = rememberCoroutineScope()
    var reloadTick by remember { mutableIntStateOf(0) }

    // The transport's own REST leg, only where a path can be read — never a second client
    // with its own auth rotation beside the one the door already holds.
    val rest = remember(app, viewModel.transportIsDirect) {
        if (!viewModel.transportIsDirect) null
        else (app?.transport as? chat.keryx.app.transport.direct.DirectTransport)?.restClient
    }

    val page by produceState<Page>(initialValue = Page.Loading, dest, reloadTick) {
        value = Page.Loading
        value = load(dest, rest, fetchMedia = { room, event -> viewModel.loadMessageMedia(room, event) })
    }

    var busy by remember { mutableStateOf(false) }
    val ready = page as? Page.Ready

    KeryxSpace(
        title = "Artifact",
        onClose = onClose,
        standalone = false,
        liveSlot = {
            Text(
                text = buildString {
                    append(dest.name)
                    ready?.let { append("  ·  ").append(humanSize(it.bytes.size)) }
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        actions = {
            // Verbs only once there is a page to act on; a dead Save on a failed load teaches
            // people not to press buttons.
            if (ready != null) {
                val tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (busy) 0.4f else 1f)
                IconButton(onClick = {
                    if (busy) return@IconButton
                    busy = true
                    scope.launch {
                        val where = saveMediaToDevice(context, ready.bytes, dest.name, MediaKind.FILE)
                        toast(context, if (where != null) "Saved to $where" else "Couldn't save")
                        busy = false
                    }
                }) { Icon(KeryxGlyphs.Download, contentDescription = "Save to device", tint = tint) }
                IconButton(onClick = {
                    if (busy) return@IconButton
                    busy = true
                    scope.launch {
                        if (!shareMedia(context, ready.bytes, dest.name, MediaKind.FILE)) toast(context, "Couldn't share")
                        busy = false
                    }
                }) { Icon(KeryxGlyphs.Share, contentDescription = "Share", tint = tint) }
                IconButton(onClick = {
                    if (busy) return@IconButton
                    busy = true
                    scope.launch {
                        openExternally(context, ready.bytes, dest.name, MediaKind.FILE)
                        busy = false
                    }
                }) { Icon(KeryxGlyphs.Exit, contentDescription = "Open with", tint = tint) }
            }
            IconButton(onClick = { reloadTick++ }) {
                Icon(
                    KeryxGlyphs.Refresh,
                    contentDescription = "Reload",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        when (val p = page) {
            Page.Loading -> Text(
                // Honest on both doors: the bytes may come through the room, not the gateway.
                "Opening ${dest.name}…",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
            is Page.Failed -> Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(
                    "Couldn't open ${dest.name}",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                )
                Text(
                    p.why,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                TextButton(onClick = { reloadTick++ }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Try again", fontSize = 12.sp)
                }
            }
            is Page.Ready -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(KeryxRadius.card)),
                contentAlignment = Alignment.Center,
            ) {
                ArtifactWebView(html = p.html, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** The loaded page, or why there is none. */
private sealed interface Page {
    data object Loading : Page
    data class Failed(val why: String) : Page
    class Ready(val html: String, val bytes: ByteArray) : Page
}

private suspend fun load(
    dest: KeryxDest.Artifact,
    rest: GatewayRest?,
    fetchMedia: suspend (roomId: String, eventId: String) -> ByteArray?,
): Page {
    val room = dest.roomId
    val event = dest.eventId
    if (room != null && event != null) {
        // The transport answers null on a failed download (it logs, never throws).
        val bytes = fetchMedia(room, event)
        if (bytes != null) return Page.Ready(bytes.decodeToString(), bytes)
        if (dest.path == null) return Page.Failed("The file didn't come through the room.")
    }
    val path = dest.path ?: return Page.Failed("Nothing to open.")
    if (rest == null) return Page.Failed("A page by path can only be read over the gateway door.")
    val head = rest.readText(path).getOrElse { return Page.Failed(it.message ?: "The gateway didn't answer.") }
    if (!head.truncated) {
        return Page.Ready(head.text, head.text.encodeToByteArray())
    }
    // Past the preview cap: the whole file, not the head of it.
    val bytes = rest.downloadFile(path).getOrElse { return Page.Failed(it.message ?: "The download failed.") }
    return Page.Ready(bytes.decodeToString(), bytes)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ArtifactWebView(html: String, modifier: Modifier = Modifier) {
    var view by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) {
        onDispose { view?.destroy(); view = null }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.setSupportMultipleWindows(false)
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setGeolocationEnabled(false)
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    // The page may link out; the viewer does not follow. The browser gets the
                    // link, the artifact stays where the eye left it.
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url ?: return false
                        if (!request.isForMainFrame) return false
                        runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        return true
                    }
                }
                view = this
            }
        },
        update = { wv ->
            // Keyed on the text itself: a recomposition with the same page must not reload it
            // and throw away the scroll; a reload that fetched new bytes must.
            if (wv.tag != html) {
                wv.tag = html
                wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
    )
}

private fun humanSize(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / (1024f * 1024f))
}

private fun toast(context: android.content.Context, text: String) {
    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
}
