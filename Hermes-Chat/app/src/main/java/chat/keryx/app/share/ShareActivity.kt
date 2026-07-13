package chat.keryx.app.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import chat.keryx.app.KeryxApp
import chat.keryx.app.domain.model.RoomProfile
import chat.keryx.app.presentation.ui.components.KeryxWordmark
import chat.keryx.app.theme.HermesChatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The system share-sheet target: anything on the phone — an article URL, a screenshot, a photo, a
 * file — flicks straight into a room with an optional note, no app-switch dance. Pick a room, add
 * a word, send. Attachments ride the MSC2530 caption path, so "what's this? + image" reaches the
 * agent as ONE turn.
 *
 * Deliberately not MainActivity: this is a five-second in-and-out surface that must not disturb
 * (or depend on) whatever the main task stack is doing. It honors the biometric lock — the room
 * list is exactly what the lock is configured to hide.
 */
class ShareActivity : androidx.fragment.app.FragmentActivity() {

    /** What arrived through the share sheet, normalized. */
    private data class Payload(val text: String, val uris: List<Uri>)

    private val locked = mutableStateOf(false)

    private fun payloadFrom(intent: Intent): Payload {
        val uris = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    },
                )
            Intent.ACTION_SEND_MULTIPLE ->
                (
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                    }
                ).orEmpty().filterNotNull()
            else -> emptyList()
        }
        // Browsers send the page title as SUBJECT and the URL as TEXT — keep both, title first.
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val combined = listOf(subject, text).filter { it.isNotBlank() }.joinToString("\n")
        return Payload(combined, uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as KeryxApp
        val settings = app.settingsRepository
        val payload = payloadFrom(intent)
        if (payload.text.isBlank() && payload.uris.isEmpty()) {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Same gate MainActivity applies: behind a configured lock, the room list stays hidden.
        val lockable = settings.biometricLockEnabled &&
            androidx.biometric.BiometricManager.from(this).canAuthenticate(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            ) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
        if (lockable) {
            locked.value = true
            promptUnlock()
        }

        // Cold share (process not running): bring the Matrix session up so rooms can load.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { app.matrixService.restore(allowInsecure = settings.allowInsecure) }
        }

        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val accent = remember { colorFromHex(settings.accentColorHex, Color(0xFFE55A00)) }
            val accent2 = remember { colorFromHex(settings.accentColor2Hex, Color(0xFF8B5CF6)) }
            HermesChatTheme(darkTheme = dark, customAccent = accent, customAccent2 = accent2) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val isLocked by locked
                    if (isLocked) {
                        LockedPane(onUnlock = { promptUnlock() })
                    } else {
                        ShareSheet(
                            payload = payload,
                            defaultRoomId = settings.lastRoomId,
                            onSend = { roomId, roomName, comment -> send(roomId, roomName, comment, payload) },
                        )
                    }
                }
            }
        }
    }

    private fun promptUnlock() {
        val prompt = androidx.biometric.BiometricPrompt(
            this,
            androidx.core.content.ContextCompat.getMainExecutor(this),
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    locked.value = false
                }
            },
        )
        prompt.authenticate(
            androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Keryx")
                .setAllowedAuthenticators(
                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build(),
        )
    }

    /** Sends and finishes. Runs in the activity scope: share URIs' read grants die with this
     *  activity, so the bytes must be read (and are sent) before it goes away. */
    private fun send(roomId: String, roomName: String, comment: String, payload: Payload) {
        val app = application as KeryxApp
        sending.value = true
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    app.matrixService.restore(allowInsecure = app.settingsRepository.allowInsecure)
                    val note = listOf(comment.trim(), payload.text)
                        .filter { it.isNotBlank() }.joinToString("\n\n")
                    if (payload.uris.isEmpty()) {
                        app.repository.sendMessage(roomId, note)
                    } else {
                        payload.uris.forEachIndexed { i, uri ->
                            val (bytes, name, mime) = readShared(uri)
                            app.repository.sendAttachment(
                                sessionId = roomId,
                                bytes = bytes,
                                fileName = name,
                                contentType = mime,
                                // MSC2530: the note rides the first attachment as its caption.
                                caption = note.takeIf { i == 0 && it.isNotBlank() },
                            )
                        }
                    }
                }
            }
            sending.value = false
            result
                .onSuccess {
                    Toast.makeText(this@ShareActivity, "Sent to $roomName", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .onFailure { e ->
                    Toast.makeText(
                        this@ShareActivity,
                        e.message?.take(120) ?: "Send failed",
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    private val sending = mutableStateOf(false)

    /** Resolve a shared content:// URI into (bytes, display name, mime type). */
    private fun readShared(uri: Uri): Triple<ByteArray, String, String> {
        val size = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
        // sendAttachment buffers the whole file; refuse what neither RAM nor the homeserver's
        // upload cap would survive, with a real message instead of an OOM.
        if (size > MAX_SHARE_BYTES) error("File too large to send (${size / (1024 * 1024)} MB)")
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
            ?: uri.lastPathSegment ?: "shared"
        val mime = contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Couldn't read $name")
        if (bytes.size > MAX_SHARE_BYTES) error("File too large to send")
        return Triple(bytes, name, mime)
    }

    @Composable
    private fun ShareSheet(
        payload: Payload,
        defaultRoomId: String?,
        onSend: (roomId: String, roomName: String, comment: String) -> Unit,
    ) {
        val app = application as KeryxApp
        // Pinned rooms first (they're pinned because they're the frequent targets), then recency.
        val pinned = remember { app.settingsRepository.pinnedRoomIds }
        val rooms by remember {
            app.repository.getRooms().map { list ->
                list.sortedWith(
                    compareByDescending<RoomProfile> { it.id in pinned }
                        .thenByDescending { it.timestamp },
                )
            }
        }.collectAsState(initial = emptyList())
        var selected by remember { mutableStateOf(defaultRoomId) }
        var comment by remember { mutableStateOf("") }
        val isSending by sending
        val accent = MaterialTheme.colorScheme.primary

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeryxWordmark(fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Share to a room",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(12.dp))

            // What's being shared, at a glance.
            val summary = buildString {
                if (payload.uris.isNotEmpty()) {
                    append(if (payload.uris.size == 1) "1 attachment" else "${payload.uris.size} attachments")
                    if (payload.text.isNotBlank()) append(" · ")
                }
                if (payload.text.isNotBlank()) append(payload.text.replace('\n', ' '))
            }
            Text(
                summary,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                fontSize = 13.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.07f))
                    .border(1.dp, accent.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            )
            Spacer(Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(rooms, key = { it.id }) { room ->
                    val sel = selected == room.id
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (sel) accent.copy(alpha = 0.18f) else accent.copy(alpha = 0.04f))
                            .border(
                                1.dp,
                                if (sel) accent.copy(alpha = 0.55f) else accent.copy(alpha = 0.12f),
                                RoundedCornerShape(10.dp),
                            )
                            .clickable(enabled = !isSending) { selected = room.id }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                    ) {
                        Text(
                            room.name,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (room.id in pinned) {
                            Text("★", color = accent.copy(alpha = 0.8f), fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it },
                enabled = !isSending,
                label = { Text("Add a note (optional)") },
                placeholder = { Text("Summarize this / remember this / …") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    val room = rooms.firstOrNull { it.id == selected } ?: return@Button
                    onSend(room.id, room.name, comment)
                },
                enabled = !isSending && rooms.any { it.id == selected },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(18.dp).height(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Sending…")
                } else {
                    Text("Send")
                }
            }
        }
    }

    @Composable
    private fun LockedPane(onUnlock: () -> Unit) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            KeryxWordmark(fontSize = 28.sp)
            Spacer(Modifier.height(10.dp))
            Text("Locked", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(20.dp))
            androidx.compose.material3.OutlinedButton(onClick = onUnlock) { Text("Unlock") }
        }
    }

    private fun colorFromHex(hex: String, fallback: Color): Color =
        runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)

    private companion object {
        /** Buffered in RAM and bounded by the homeserver upload cap — refuse politely past this. */
        const val MAX_SHARE_BYTES = 64L * 1024 * 1024
    }
}
