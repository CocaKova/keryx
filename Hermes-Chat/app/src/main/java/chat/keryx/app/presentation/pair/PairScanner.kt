package chat.keryx.app.presentation.pair

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import chat.keryx.app.presentation.ui.components.KeryxType
import chat.keryx.core.pair.PairingLink
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

/**
 * Pairing by QR (3.0): the scanner for the code `hermes keryx pair` prints, and the pieces the
 * sign-in forms show around it.
 *
 * The camera is asked for only when Scan is tapped. Frames are decoded on the phone by ZXing
 * (no Google Play services) and dropped as soon as they're read; nothing is stored or sent. The
 * first valid pairing code ends the scan; anything else (another QR, a damaged or forged code)
 * says why and keeps scanning.
 */
@Composable
fun PairScanDialog(onDismiss: () -> Unit, onScanned: (PairingLink) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(hasCamera(context)) }
    var asked by remember { mutableStateOf(false) }
    // What the last screenshot said when it wasn't a usable code.
    var pickedNote by remember { mutableStateOf<String?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    // A screenshot of the code (3.0): for `hermes keryx pair` run over SSH on this very phone,
    // or a phone without a camera. The system photo picker: no storage permission.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val parsed = PairingLink.parse(decodeImage(context, uri))) {
                is PairingLink.Parsed.Ok -> onScanned(parsed.pairing)
                is PairingLink.Parsed.Rejected -> pickedNote =
                    if (parsed.why == PairingLink.NOT_PAIRING) "No pairing code found in that image." else parsed.why
            }
        }
    }
    val pickScreenshot = {
        pick.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when {
                granted -> ScannerBody(onScanned, pickScreenshot, pickedNote)
                asked -> NoCamera(
                    onRetry = { ask.launch(Manifest.permission.CAMERA) },
                    onPick = pickScreenshot,
                    note = pickedNote,
                    onDismiss = onDismiss,
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.safeDrawingPadding().padding(8.dp).align(Alignment.TopStart),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close the scanner", tint = Color.White)
            }
        }
    }
}

@Composable
private fun ScannerBody(onScanned: (PairingLink) -> Unit, onPick: () -> Unit, pickedNote: String?) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = chat.keryx.app.presentation.ui.components.LocalKeryxHaptics.current
    val accent = MaterialTheme.colorScheme.primary
    val scanned by rememberUpdatedState(onScanned)
    var note by remember { mutableStateOf<String?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val done = AtomicBoolean(false)
        var lastText: String? = null
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { a ->
                a.setAnalyzer(executor, QrAnalyzer { text ->
                    if (done.get() || text == lastText) return@QrAnalyzer
                    lastText = text
                    when (val parsed = PairingLink.parse(text)) {
                        is PairingLink.Parsed.Ok -> if (done.compareAndSet(false, true)) {
                            ContextCompat.getMainExecutor(context).execute {
                                haptics.commit()
                                scanned(parsed.pairing)
                            }
                        }
                        is PairingLink.Parsed.Rejected ->
                            ContextCompat.getMainExecutor(context).execute { note = parsed.why }
                    }
                })
            }
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val provider = future.get()
                provider.unbind(preview, analysis)
                camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { note = "The camera wouldn't start. Close this and type the address instead." }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            done.set(true)
            runCatching { future.get().unbind(preview, analysis) }
            analysis.clearAnalyzer()
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // The window: everything outside a centred square is dimmed, the square is clear.
        Canvas(
            Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            val side = size.minDimension * 0.68f
            val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f - size.height * 0.06f)
            drawRect(Color.Black.copy(alpha = 0.62f))
            drawRoundRect(Color.Transparent, topLeft, Size(side, side), CornerRadius(28.dp.toPx()), blendMode = BlendMode.Clear)
            drawRoundRect(
                accent, topLeft, Size(side, side), CornerRadius(28.dp.toPx()),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()),
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Scan a pairing code",
                color = Color.White,
                fontSize = KeryxType.headline,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                buildCommandHint(),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = KeryxType.body,
                textAlign = TextAlign.Center,
            )
            (pickedNote ?: note)?.let {
                Surface(color = Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(12.dp)) {
                    Text(
                        it,
                        color = Color(0xFFFFC56B),
                        fontSize = KeryxType.body,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (camera?.cameraInfo?.hasFlashUnit() == true) {
                    TextButton(onClick = {
                        torch = !torch
                        camera?.cameraControl?.enableTorch(torch)
                    }) {
                        Icon(
                            if (torch) Icons.Filled.FlashlightOff else Icons.Filled.FlashlightOn,
                            contentDescription = null,
                            tint = Color.White,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (torch) "Light off" else "Light on", color = Color.White, fontSize = KeryxType.bodyLarge)
                    }
                }
                TextButton(onClick = onPick) {
                    Icon(Icons.Outlined.Image, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Use a screenshot", color = Color.White, fontSize = KeryxType.bodyLarge)
                }
            }
        }
    }
}

private fun buildCommandHint() =
    "On your gateway, run hermes keryx pair, then point the camera at the code it shows."

@Composable
private fun NoCamera(onRetry: () -> Unit, onPick: () -> Unit, note: String?, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            "Keryx needs the camera to read the code. It only looks while this screen is open, and keeps nothing it sees.",
            color = Color.White,
            fontSize = KeryxType.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text("Allow the camera", fontSize = KeryxType.titleLarge) }
        TextButton(onClick = onPick) {
            Text("Use a screenshot of the code", color = Color.White, fontSize = KeryxType.bodyLarge)
        }
        note?.let {
            Text(it, color = Color(0xFFFFC56B), fontSize = KeryxType.body, textAlign = TextAlign.Center)
        }
        TextButton(onClick = onDismiss) {
            Text("Type the address instead", color = Color.White, fontSize = KeryxType.bodyLarge)
        }
    }
}

/** One QR read per frame, on the analysis thread. Only the luminance plane is used. */
private class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        image.use {
            val plane = it.planes[0]
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining()).also { b -> buffer.get(b) }
            QrDecode.luminance(bytes, plane.rowStride, it.width, it.height)?.let(onText)
        }
    }
}

/** A picked screenshot, decoded off the main thread; the bitmap is dropped after the read. */
private suspend fun decodeImage(context: Context, uri: android.net.Uri): String? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            // A QR in a screenshot survives halving; a 50 MP photo doesn't need to be held whole.
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            } ?: return@runCatching null
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val w = bitmap.width
            val h = bitmap.height
            bitmap.recycle()
            QrDecode.argb(pixels, w, h)
        }.getOrNull()
    }

private fun hasCamera(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** What the gateway lists this phone as: the name the owner gave it, else the model. */
fun phoneName(context: Context): String =
    runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL

/** The way in, on both sign-in forms. */
@Composable
fun ScanPairingButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Scan a pairing code", fontSize = KeryxType.titleLarge)
        }
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                append("From ")
                withStyle(androidx.compose.ui.text.SpanStyle(fontFamily = FontFamily.Monospace)) { append("hermes keryx pair") }
                append(" on your gateway, or type it in below.")
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = KeryxType.caption,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * What was scanned, before anything connects: the host it will talk to, and a warning when the
 * traffic wouldn't be encrypted. Connecting is still the form's own button.
 */
@Composable
fun PairingBanner(pairing: PairingLink, onCancel: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        color = accent.copy(alpha = 0.08f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().border(BorderStroke(1.dp, accent.copy(alpha = 0.35f)), RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Pairing with ${pairing.name}",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = KeryxType.title,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        pairing.host,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = KeryxType.caption,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                TextButton(onClick = onCancel, enabled = enabled) { Text("Cancel", fontSize = KeryxType.body) }
            }
            if (pairing.unencrypted) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.WarningAmber, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Not encrypted: this is plain http to an internet address. Pair over your home network or Tailscale instead if you can.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = KeryxType.caption,
                    )
                }
            }
            Text(
                "If your gateway has a sign-in page, it opens next. This phone then gets a Link token of its own; the gateway's key never leaves it.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = KeryxType.caption,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
