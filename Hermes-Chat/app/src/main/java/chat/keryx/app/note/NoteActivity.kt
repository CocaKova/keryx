package chat.keryx.app.note

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import chat.keryx.app.KeryxApp
import chat.keryx.app.R
import chat.keryx.app.presentation.ui.components.KeryxGlyphs
import chat.keryx.app.presentation.ui.components.KeryxRadius
import chat.keryx.app.presentation.ui.components.KeryxWordmark
import chat.keryx.app.theme.HermesChatTheme
import chat.keryx.app.transport.direct.DirectTransport
import chat.keryx.app.widget.KeryxWidget
import chat.keryx.core.model.BotProfile
import chat.keryx.core.model.RoomProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The note (2.13 Part B): one line to the agent, from the Quick Settings tile, over whatever
 * you were doing. A dialog-themed activity, out of Recents, gone the moment the note is sent.
 *
 * It sends on the share sheet's path — `transport.sendMessage(roomId, text)`, the same call
 * [chat.keryx.app.share.ShareActivity] makes for shared text — to the pinned or most recent
 * session by default, with a row to change that. Deliberately not the share sheet itself: a
 * note has nothing to attach and no room to browse; it is typed and gone. The lock the share
 * sheet honours is honoured here too — the session names are exactly what the lock hides.
 */
class NoteActivity : androidx.fragment.app.FragmentActivity() {

    private val locked = mutableStateOf(false)
    private val sending = mutableStateOf(false)

    // True while the unlock prompt is up. On API 26–29 a PIN/pattern unlock is the system's
    // own confirm-credential ACTIVITY, which stops this one — so leaving is not "stopped".
    private var unlocking = false

    /**
     * Gone the moment you leave it — except to unlock it. This was `noHistory`, which finished
     * the sheet as soon as the credential screen covered it: the note vanished mid-unlock.
     */
    override fun onStop() {
        super.onStop()
        if (!unlocking && !isChangingConfigurations) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as KeryxApp
        val settings = app.settingsRepository

        val lockable = settings.biometricLockEnabled &&
            androidx.biometric.BiometricManager.from(this).canAuthenticate(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            ) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
        if (lockable) {
            locked.value = true
            promptUnlock()
        }

        // Cold open on the Matrix door: bring the session up so the roster can load.
        if (!app.isDirectTransport) lifecycleScope.launch(Dispatchers.IO) {
            runCatching { app.matrixService.restore(allowInsecure = settings.allowInsecure) }
        }

        setContent {
            val dark = isSystemInDarkTheme()
            val accent = remember { colorFromHex(settings.accentColorHex, Color(0xFFE55A00)) }
            val accent2 = remember { colorFromHex(settings.accentColor2Hex, Color(0xFF8B5CF6)) }
            HermesChatTheme(darkTheme = dark, customAccent = accent, customAccent2 = accent2) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(KeryxRadius.sheet),
                    modifier = Modifier.fillMaxWidth().imePadding(),
                ) {
                    val isLocked by locked
                    if (isLocked) LockedPane(onUnlock = { promptUnlock() })
                    else NoteSheet(defaultRoomId = settings.lastRoomId, onSend = ::send)
                }
            }
        }
    }

    private fun promptUnlock() {
        unlocking = true
        val prompt = androidx.biometric.BiometricPrompt(
            this,
            androidx.core.content.ContextCompat.getMainExecutor(this),
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    unlocking = false
                    locked.value = false
                }

                // Cancelled, locked out, or dismissed: the sheet stays on its locked pane, and
                // leaving it from here finishes it as usual.
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    unlocking = false
                }
            },
        )
        prompt.authenticate(
            androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.note_unlock_title))
                .setAllowedAuthenticators(
                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build(),
        )
    }

    /** Send and finish; a failure keeps the sheet open with the words still in it. */
    private fun send(room: RoomProfile, text: String) {
        val app = application as KeryxApp
        sending.value = true
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (!app.isDirectTransport) app.matrixService.restore(allowInsecure = app.settingsRepository.allowInsecure)
                    app.transport.sendMessage(room.id, text.trim())
                }
            }
            sending.value = false
            result
                .onSuccess {
                    Toast.makeText(this@NoteActivity, getString(R.string.note_sent_to, room.name), Toast.LENGTH_SHORT).show()
                    // The card on the home screen now has a newer last line: yours.
                    KeryxWidget.refresh(this@NoteActivity)
                    finish()
                }
                .onFailure { e ->
                    Toast.makeText(this@NoteActivity, e.message?.take(120) ?: getString(R.string.note_send_failed), Toast.LENGTH_LONG).show()
                }
        }
    }

    @Composable
    private fun NoteSheet(defaultRoomId: String?, onSend: (RoomProfile, String) -> Unit) {
        val app = application as KeryxApp
        val direct = app.transport as? DirectTransport
        // The share sheet's order: pinned first (they are pinned because they are where notes
        // go), then recency. Matrix pins live in the phone's ledger, gateway pins on the row.
        val ledger = remember { if (app.isDirectTransport) emptySet() else app.settingsRepository.pinnedRoomIds }
        val isPinned: (RoomProfile) -> Boolean = { it.pinned || it.id in ledger }
        val rooms by remember {
            app.transport.getRooms().map { list ->
                list.sortedWith(compareByDescending<RoomProfile> { isPinned(it) }.thenByDescending { it.timestamp })
            }
        }.collectAsState(initial = emptyList())
        val agents by remember(direct) { direct?.agents() ?: MutableStateFlow<List<BotProfile>>(emptyList()) }.collectAsState()
        val agentName = remember(agents) { NoteTileService.agentName(agents) }

        var chosen by remember { mutableStateOf<String?>(null) }
        var picking by remember { mutableStateOf(false) }
        var text by remember { mutableStateOf("") }
        val isSending by sending
        // The default: the last room you were in if it is still listed, else the head of the
        // sorted roster — the pinned favourite, or failing that the newest.
        val target = rooms.firstOrNull { it.id == chosen }
            ?: rooms.firstOrNull { it.id == defaultRoomId }
            ?: rooms.firstOrNull()
        val ink = MaterialTheme.colorScheme.onSurface
        val faded = MaterialTheme.colorScheme.onSurfaceVariant
        val accent = MaterialTheme.colorScheme.primary
        val focus = remember { FocusRequester() }
        val canSend = target != null && text.isNotBlank() && !isSending
        fun commit() { if (canSend) onSend(target!!, text) }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeryxWordmark(fontSize = 18.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (agentName != null) stringResourceTo(agentName) else getString(R.string.note_tile_label),
                    color = faded,
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(12.dp))

            // Where it lands. One row; tap to swap it for the roster.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(KeryxRadius.chip))
                    .background(accent.copy(alpha = 0.07f))
                    .border(1.dp, accent.copy(alpha = 0.2f), RoundedCornerShape(KeryxRadius.chip))
                    .clickable(enabled = !isSending && rooms.size > 1) { picking = !picking }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(getString(R.string.note_target_prefix), color = faded, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    target?.name ?: getString(R.string.note_no_sessions),
                    color = ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (target != null && isPinned(target)) {
                    Icon(KeryxGlyphs.PinFilled, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                }
                if (rooms.size > 1) {
                    Icon(KeryxGlyphs.ChevronRight, contentDescription = getString(R.string.note_change_target), tint = faded, modifier = Modifier.size(16.dp))
                }
            }
            if (picking) {
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(rooms, key = { it.id }) { room ->
                        val sel = room.id == target?.id
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(KeryxRadius.chip))
                                .background(if (sel) accent.copy(alpha = 0.18f) else accent.copy(alpha = 0.04f))
                                .clickable(enabled = !isSending) { chosen = room.id; picking = false }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(
                                room.name,
                                color = ink,
                                fontSize = 13.sp,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (isPinned(room)) Icon(KeryxGlyphs.PinFilled, contentDescription = null, tint = accent, modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // The line. Send on the keyboard's own key, or the arrow.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(KeryxRadius.field))
                        .background(ink.copy(alpha = 0.06f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) {
                        Text(getString(R.string.note_placeholder), fontSize = 14.sp, color = ink.copy(alpha = 0.45f), maxLines = 1)
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        enabled = !isSending,
                        textStyle = TextStyle(fontSize = 14.sp, color = ink, lineHeight = 19.sp),
                        cursorBrush = SolidColor(accent),
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { commit() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(KeryxRadius.field))
                        .background(if (canSend) accent else ink.copy(alpha = 0.08f))
                        .clickable(enabled = canSend) { commit() },
                ) {
                    if (isSending) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = ink)
                    } else {
                        Icon(
                            KeryxGlyphs.ArrowUp,
                            contentDescription = getString(R.string.note_send),
                            tint = if (canSend) MaterialTheme.colorScheme.onPrimary else faded,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        // The keyboard is the point of this surface: up with it.
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }

    /** "Note to <agent>" — the tile's own words, so the sheet and the tile agree. */
    private fun stringResourceTo(agent: String): String = getString(R.string.note_tile_label_to, agent)

    @Composable
    private fun LockedPane(onUnlock: () -> Unit) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        ) {
            KeryxWordmark(fontSize = 24.sp)
            Spacer(Modifier.height(8.dp))
            Text(getString(R.string.note_locked), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.OutlinedButton(onClick = onUnlock) { Text(getString(R.string.note_unlock)) }
        }
    }

    private fun colorFromHex(hex: String, fallback: Color): Color =
        runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)
}
