package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.keryx.app.presentation.ChatViewModel
import kotlin.math.atan2

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    currentAccentColor: Color,
    onAccentColorChanged: (Color) -> Unit,
    currentAccentColor2: Color,
    onAccentColor2Changed: (Color) -> Unit,
    currentUserId: String?,
    matrixUrl: String,
    onMatrixUrlChanged: (String) -> Unit,
    agentMatrixId: String,
    onAgentMatrixIdChanged: (String) -> Unit,
    matrixToken: String,
    onMatrixTokenChanged: (String) -> Unit,
    allowInsecure: Boolean,
    onAllowInsecureChanged: (Boolean) -> Unit,
    gatewayUrl: String,
    onGatewayUrlChanged: (String) -> Unit,
    gatewayApiKey: String,
    onGatewayApiKeyChanged: (String) -> Unit,
    sideChannelEnabled: Boolean,
    onSideChannelEnabledChanged: (Boolean) -> Unit,
    sttUrl: String,
    onSttUrlChanged: (String) -> Unit,
    sttApiKey: String,
    onSttApiKeyChanged: (String) -> Unit,
    sttModel: String,
    onSttModelChanged: (String) -> Unit,
    ttsAutoSpeak: Boolean,
    onTtsAutoSpeakChanged: (Boolean) -> Unit,
    ttsUrl: String,
    onTtsUrlChanged: (String) -> Unit,
    ttsApiKey: String,
    onTtsApiKeyChanged: (String) -> Unit,
    ttsVoice: String,
    onTtsVoiceChanged: (String) -> Unit,
    ttsModel: String,
    onTtsModelChanged: (String) -> Unit,
    onTestLink: () -> Unit,
    showTelemetry: Boolean,
    onShowTelemetryChanged: (Boolean) -> Unit,
    missionAlertsEnabled: Boolean,
    onMissionAlertsChanged: (Boolean) -> Unit,
    pushEnabled: Boolean,
    onPushEnabledChanged: (Boolean) -> Unit,
    pushGatewayUrl: String,
    onPushGatewayUrlChanged: (String) -> Unit,
    biometricLockEnabled: Boolean,
    onBiometricLockChanged: (Boolean) -> Unit,
    e2eeEnabled: Boolean,
    onE2eeChanged: (Boolean) -> Unit,
    hapticsEnabled: Boolean,
    onHapticsChanged: (Boolean) -> Unit,
    animationStyle: String,
    onAnimationStyleChanged: (String) -> Unit,
    bubbleStyle: String,
    onBubbleStyleChanged: (String) -> Unit,
    messageTextScale: Float,
    onMessageTextScaleChanged: (Float) -> Unit,
    onResetAppearance: () -> Unit,
    onLoginRequested: (String, String, (Boolean, String?) -> Unit) -> Unit,
    onLogout: () -> Unit,
    onDismissRequest: () -> Unit
) {
    // 2.0 Phase 4: Settings is a place on the nav stack, not a Dialog window — the host owns
    // its window, transition, and back gesture. The internal spoke BackHandler composes later
    // than the host's handler, so back still walks spoke → hub → out of the space.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
          Box(Modifier.fillMaxSize().keryxDuskSky()) {
            // Hub-and-spoke: null = the section list; a name = that section's page.
            // One long scroll of seven dense cards was the old layout — cluttered.
            var section by remember { mutableStateOf<String?>(null) }
            BackHandler(enabled = section != null) { section = null }
            val direct = viewModel.transportIsDirect
            // The row to land on (2.10): from the hub's own search, or from the drawer palette
            // by way of the ViewModel. Consumed once the row has been scrolled to and lit.
            var focusId by remember { mutableStateOf<String?>(null) }
            val jump by viewModel.settingsJump.collectAsState()
            LaunchedEffect(jump) {
                val id = jump ?: return@LaunchedEffect
                val entry = SettingsCatalog.byId(id)
                val target = entry?.section(direct)
                if (target != null) { section = target; focusId = entry.id }
                viewModel.consumeSettingsJump()
            }
            val scrollState = rememberScrollState()
            var contentTop by remember { mutableStateOf(0f) }
            val settingsFocus = remember(focusId, scrollState) {
                SettingsFocus(target = focusId, scroll = scrollState, contentTop = { contentTop }) { focusId = null }
            }
            var query by remember { mutableStateOf("") }

            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                TopAppBar(
                    title = {
                        Text(
                            (section ?: "Settings").uppercase(),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 5.sp,
                        )
                    },
                    navigationIcon = {
                        if (section != null) {
                            IconButton(onClick = { section = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        } else {
                            IconButton(onClick = onDismissRequest) {
                                Icon(Icons.Default.Close, contentDescription = "Close")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )

                CompositionLocalProvider(LocalSettingsFocus provides settingsFocus) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .onGloballyPositioned { contentTop = it.positionInRoot().y - scrollState.value }
                        .padding(horizontal = 16.dp)
                ) {
                    // Gateway-backed state for the Agent and Companion sections (and their hub
                    // subtitles). Cheap to collect here — both flows are already hot for the
                    // Gateway space and the drawer mascot.
                    val caps by viewModel.hub.reasoningCaps.collectAsState()
                    val hubHealth by viewModel.hub.health.collectAsState()
                    val petInfo by viewModel.pet.petInfo.collectAsState()

                    if (section == null) {
                        Spacer(Modifier.height(4.dp))
                        // Search (2.10): every row in the app, by name or by the words you'd
                        // use for it; a hit opens its section scrolled to the row, lit for a
                        // beat. The Desktop's settings search, without the schema.
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Find a setting…") },
                            leadingIcon = { Icon(KeryxGlyphs.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            trailingIcon = if (query.isNotBlank()) {
                                { TextButton(onClick = { query = "" }) { Text("Clear", fontSize = 11.sp) } }
                            } else null,
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        )
                        val hits = remember(query, direct) { SettingsCatalog.search(query, direct) }
                        if (query.isNotBlank()) {
                            if (hits.isEmpty()) Text(
                                "Nothing by that name",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(8.dp),
                            )
                            hits.forEach { e ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { section = e.section(direct); focusId = e.id; query = "" }
                                        .padding(horizontal = 10.dp, vertical = 12.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(e.title, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface)
                                        Text(e.section(direct).orEmpty(), fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(KeryxGlyphs.ChevronRight, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(18.dp))
                                }
                            }
                        } else {
                        KeryxHubRow(Icons.Default.Person, "Account",
                            currentUserId ?: "Not signed in") { section = "Account" }
                        KeryxHubRow(Icons.Default.Memory, "Agent",
                            caps?.model?.takeIf { it.isNotBlank() } ?: "Brain, telemetry & alerts") { section = "Agent" }
                        KeryxHubRow(Icons.Default.Pets, "Companion",
                            petInfo?.displayName?.takeIf { it.isNotBlank() } ?: "The drawer mascot") { section = "Companion" }
                        // One wire, one row (2.10): on the direct door the gateway IS the
                        // connection and Hermes Link is its API server, so they share a page.
                        // Matrix keeps two — the homeserver is one thing, the link another.
                        if (direct) {
                            val fleet by viewModel.fleet.collectAsState()
                            val where = fleet.active?.let { a ->
                                if (fleet.hasChoice) "${a.name} · ${fleet.size} gateways" else "${a.name} · ${a.hostLabel}"
                            } ?: viewModel.directGatewayUrl.ifBlank { "Address, certificates & Hermes Link" }
                                .removePrefix("https://").removePrefix("http://")
                            KeryxHubRow(Icons.Default.Dns, "Gateways",
                                where + (if (sideChannelEnabled) " · linked" else "")) { section = "Gateway" }
                        }
                        else {
                            KeryxHubRow(Icons.Default.Dns, "Connection",
                                matrixUrl.ifBlank { "Homeserver & agent" }) { section = "Connection" }
                            KeryxHubRow(Icons.Default.Bolt, "Hermes Link",
                                if (sideChannelEnabled) "Live token streaming on" else "Live token streaming off") { section = "Hermes Link" }
                        }
                        KeryxHubRow(Icons.Default.Mic, "Voice",
                            listOfNotNull(
                                if (sttUrl.isNotBlank()) "Dictation" else null,
                                if (ttsAutoSpeak) "Auto-speak" else null,
                            ).ifEmpty { listOf("Dictation & spoken replies") }.joinToString(" · ")) { section = "Voice" }
                        KeryxHubRow(Icons.Default.Palette, "Appearance",
                            "Theme, bubbles, text, accents, motion") { section = "Appearance" }
                        KeryxHubRow(Icons.Default.Lock, "Privacy & Security",
                            listOfNotNull(
                                if (biometricLockEnabled) "App lock" else null,
                                if (e2eeEnabled && !direct) "E2EE" else null,
                            ).ifEmpty { listOf(if (direct) "App lock & senses" else "App lock & encryption") }.joinToString(" · ")) { section = "Privacy & Security" }
                        if (direct) {
                            val archived by viewModel.archivedRooms.collectAsState()
                            KeryxHubRow(KeryxGlyphs.Stack, "Sessions",
                                if (archived.isEmpty()) "Archived & pruning" else "${archived.size} archived") { section = "Sessions" }
                        }
                        KeryxHubRow(Icons.Default.BugReport, "About",
                            "Keryx v${chat.keryx.app.BuildConfig.VERSION_NAME}" +
                                (hubHealth.data?.version?.takeIf { it.isNotBlank() }?.let { " · hermes $it" } ?: "")) { section = "About" }
                        }
                    }

                    // --- Account ---
                    if (section == "Account") SettingsCard("Account", anchor = SettingsRow.ACCOUNT_IDENTITY) {
                        Text(
                            text = currentUserId ?: "Not signed in",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        )
                        Text(
                            text = if (viewModel.transportIsDirect) viewModel.directGatewayUrl.ifBlank { "No gateway set" }
                            else matrixUrl.ifBlank { "No homeserver set" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(
                            onClick = onLogout,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Sign out")
                        }
                    }

                    // --- Transport: the two doors, toggleable without touching either session ---
                    // (Phase 4's second door, made a switch: the Matrix session stays in
                    // Trixnity's store, the sealed direct token stays in prefs — flipping the
                    // door just decides which spine the next process life boots.)
                    if (section == "Account") SettingsCard("Transport", anchor = SettingsRow.ACCOUNT_TRANSPORT) {
                        val direct = viewModel.transportIsDirect
                        val otherReady =
                            if (direct) viewModel.matrixSessionOnFile else viewModel.directCredentialsOnFile
                        var confirmSwitch by remember { mutableStateOf(false) }
                        val settingsContext = androidx.compose.ui.platform.LocalContext.current
                        Text(
                            text = if (direct) "Direct to gateway" else "Matrix",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        )
                        Text(
                            text = buildString {
                                append(if (direct) "No homeserver — straight to the hermes gateway." else "The herald's home — E2EE rooms over your homeserver.")
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = when {
                                direct && otherReady -> "Switching boots the Matrix door — a signed-in session resumes there."
                                direct -> "No Matrix session on file — you'll sign in after the switch."
                                otherReady -> "Gateway credentials are on file — switching connects with them."
                                else -> "No gateway credentials yet — you'll connect after the switch."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { confirmSwitch = true }) {
                            Text(if (direct) "Switch to Matrix" else "Switch to direct gateway")
                        }
                        if (confirmSwitch) {
                            AlertDialog(
                                onDismissRequest = { confirmSwitch = false },
                                title = { Text(if (direct) "Switch to Matrix?" else "Switch to the direct gateway?") },
                                text = {
                                    Text(
                                        "Keryx restarts on the other transport. Nothing is signed out — " +
                                            "both sessions stay stored, and you can switch back the same way.",
                                    )
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        viewModel.switchTransport(if (direct) "matrix" else "direct")
                                        relaunchApp(settingsContext)
                                    }) { Text("Switch & restart") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { confirmSwitch = false }) { Text("Cancel") }
                                },
                            )
                        }
                    }

                    // --- Agent (gateway-backed: what's running, and how it speaks up) ---
                    if (section == "Agent") {
                        LaunchedEffect(Unit) {
                            viewModel.hub.refreshReasoningCaps()
                            viewModel.hub.refreshHealth()
                        }
                        SettingsCard("Brain") {
                            val rows = listOfNotNull(
                                caps?.model?.takeIf { it.isNotBlank() }?.let { "Model" to it },
                                caps?.let {
                                    "Reasoning" to (it.labels[it.current] ?: it.current.ifBlank { "—" })
                                },
                                hubHealth.data?.version?.takeIf { it.isNotBlank() }?.let { "Gateway" to "hermes-agent $it" },
                                hubHealth.data?.gatewayState?.takeIf { it.isNotBlank() }?.let { "State" to it },
                            )
                            if (rows.isEmpty()) {
                                Text(
                                    hubHealth.error ?: "Probing the gateway…",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                )
                            } else rows.forEach { (k, v) ->
                                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                                    Text(k, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(86.dp))
                                    Text(v, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Live console — controls, jobs, sessions, skills and tools live in the Gateway.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                fontSize = 11.sp,
                            )
                        }
                        SettingsCard("Presence") {
                            SettingsSwitchRow(
                                anchor = SettingsRow.AGENT_TELEMETRY,
                                title = "Show telemetry",
                                subtitle = "Automated check-ins and the runtime footer as quiet blocks. The self-improvement review always shows",
                                checked = showTelemetry,
                                onCheckedChange = onShowTelemetryChanged,
                            )
                            SettingsSwitchRow(
                                anchor = SettingsRow.AGENT_ALERTS,
                                title = "Mission alerts",
                                subtitle = "Notify when a mission completes, blocks, or gives up — checked in the background every 15 minutes",
                                checked = missionAlertsEnabled,
                                onCheckedChange = onMissionAlertsChanged,
                            )
                            val resume by viewModel.resumeLastRoom.collectAsState()
                            SettingsSwitchRow(
                                anchor = SettingsRow.AGENT_RESUME,
                                title = "Reopen last chat on launch",
                                subtitle = "Off: every cold start begins on the drawer",
                                checked = resume,
                                onCheckedChange = { viewModel.setResumeLastRoom(it) },
                            )
                            if (direct) {
                                val sticky by viewModel.stickyModel.collectAsState()
                                SettingsSwitchRow(
                                    anchor = SettingsRow.AGENT_STICKY_MODEL,
                                    title = "New sessions use the last model picked",
                                    subtitle = "Off: every new session starts on the gateway's default",
                                    checked = sticky,
                                    onCheckedChange = { viewModel.setStickyModel(it) },
                                )
                            }
                        }
                    }

                    // --- Companion (the petdex mascot) ---
                    if (section == "Companion") SettingsCard("Companion", anchor = SettingsRow.COMPANION_PICK) {
                        LaunchedEffect(Unit) { viewModel.pet.refreshPet() }
                        var showPetPicker by remember { mutableStateOf(false) }
                        if (showPetPicker) {
                            PetPickerSheet(viewModel = viewModel, onDismiss = { showPetPicker = false })
                        }
                        val pet = petInfo
                        if (pet != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PetSprite(
                                    info = pet,
                                    pose = PetPose.IDLE,
                                    running = true,
                                    modifier = Modifier.height(52.dp).width(48.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(pet.displayName.ifBlank { pet.slug },
                                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface)
                                    Text("Lives in the drawer header — runs while the agent works",
                                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } else {
                            Text(
                                "No companion adopted yet — pick one from the gateway's petdex.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { showPetPicker = true }) {
                            Text(if (petInfo != null) "Choose a different companion" else "Choose a companion")
                        }
                    }

                    // --- Connection ---
                    // Direct door: no homeserver, no agent ids, no push gateway — the gateway IS
                    // the connection. What remains is where it lives (set at sign-in; changed by
                    // signing out) and the one switch that applies to it.
                    // The fleet (2.11): the Desktop's Settings → Gateways registry. One card holds
                    // every gateway the phone can reach; the row that used to say "sign out and
                    // connect again" is now "Add gateway".
                    if (section == "Gateway") SettingsCard("Gateways", anchor = SettingsRow.CONNECTION_GATEWAY) {
                        val settingsContext = androidx.compose.ui.platform.LocalContext.current
                        GatewayRegistry(viewModel, onRelaunch = { relaunchApp(settingsContext) })
                        val fleet by viewModel.fleet.collectAsState()
                        if (fleet.hasChoice) {
                            Spacer(Modifier.height(8.dp))
                            SettingsSwitchRow(
                                anchor = SettingsRow.CONNECTION_FLEET_RESUME,
                                title = "At startup, return to the last-used gateway",
                                subtitle = if (fleet.resumeLastGateway) "A cold start opens where you left off"
                                else "A cold start opens on Primary — the Desktop's default",
                                checked = fleet.resumeLastGateway,
                                onCheckedChange = viewModel::setResumeLastGateway,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        SettingsSwitchRow(
                            anchor = SettingsRow.CONNECTION_INSECURE,
                            title = "Allow self-signed certificates",
                            subtitle = "Only for local / self-hosted gateways",
                            checked = allowInsecure,
                            onCheckedChange = onAllowInsecureChanged,
                        )
                    }
                    if (section == "Connection") SettingsCard("Connection", anchor = SettingsRow.CONNECTION_HOMESERVER) {
                        OutlinedTextField(
                            value = matrixUrl,
                            onValueChange = onMatrixUrlChanged,
                            label = { Text("Homeserver URL") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = agentMatrixId,
                            onValueChange = onAgentMatrixIdChanged,
                            label = { Text("Hermes Agent Matrix IDs") },
                            placeholder = { Text("@hermes:example.com, @milo:example.com") },
                            supportingText = {
                                Text(
                                    "Which senders get agent rendering. Separate several with commas " +
                                        "to seat a council — the first one is your primary herald."
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        SettingsAnchor(SettingsRow.CONNECTION_HERALDS) {
                        HeraldsList(
                            agentMatrixId = agentMatrixId,
                            overrides = viewModel.heraldAccents.collectAsState().value,
                            onSetAccent = { key, hex -> viewModel.setHeraldAccent(key, hex) },
                        )
                        }
                        Spacer(Modifier.height(8.dp))
                        SettingsSwitchRow(
                            anchor = SettingsRow.CONNECTION_INSECURE,
                            title = "Allow self-signed certificates",
                            subtitle = "Only for local / self-hosted servers",
                            checked = allowInsecure,
                            onCheckedChange = onAllowInsecureChanged,
                        )
                        SettingsSwitchRow(
                            anchor = SettingsRow.CONNECTION_PUSH,
                            title = "Push notifications",
                            subtitle = when {
                                !pushEnabled -> "Off — notifications rely on the in-app sync staying alive"
                                org.unifiedpush.android.connector.UnifiedPush
                                    .getDistributors(androidx.compose.ui.platform.LocalContext.current)
                                    .isNotEmpty() -> "Via your UnifiedPush distributor"
                                else -> "Built-in — Keryx holds its own connection to your push server"
                            },
                            checked = pushEnabled,
                            onCheckedChange = onPushEnabledChanged,
                        )
                        // Progressive disclosure: the gateway URL only matters while push is on.
                        if (pushEnabled) {
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = pushGatewayUrl,
                                onValueChange = onPushGatewayUrlChanged,
                                label = { Text("Push gateway URL") },
                                placeholder = { Text("https://ntfy.example.com") },
                                supportingText = {
                                    Text("A Matrix push gateway — a self-hosted ntfy server works out of the box", fontSize = 11.sp)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }

                        var reauth by remember { mutableStateOf(false) }
                        SettingsAnchor(SettingsRow.CONNECTION_REAUTH) {
                        TextButton(onClick = { reauth = !reauth }) {
                            Text(if (reauth) "Hide re-authenticate" else "Re-authenticate")
                        }
                        }
                        if (reauth) {
                            var usernameInput by remember { mutableStateOf("") }
                            var passwordInput by remember { mutableStateOf("") }
                            var passwordVisible by remember { mutableStateOf(false) }
                            var testStatus by remember { mutableStateOf("") }
                            OutlinedTextField(
                                value = usernameInput,
                                onValueChange = { usernameInput = it },
                                label = { Text("Username") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text("Password") },
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Text(if (passwordVisible) "Hide" else "Show", fontSize = 12.sp)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    if (usernameInput.isBlank() || passwordInput.isBlank()) {
                                        testStatus = "Enter username and password first."
                                    } else {
                                        testStatus = "Authenticating…"
                                        onLoginRequested(usernameInput, passwordInput) { success, msg ->
                                            testStatus = msg ?: if (success) "Success!" else "Failed."
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Login") }
                            if (testStatus.isNotEmpty()) {
                                Text(testStatus, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }

                    // --- Hermes Link (side-channel streaming) ---
                    if (section == "Hermes Link" || section == "Gateway") SettingsCard("Hermes Link", anchor = SettingsRow.LINK_URL) {
                        // The same link serves two jobs by door. On Matrix it is the SSE
                        // side-channel that streams tokens ahead of sync. On the direct door the
                        // websocket already streams; the link is the API server the spaces ride
                        // (Missions, Runs, Shipyard, the Gateway space, the pet).
                        SettingsSwitchRow(
                            anchor = SettingsRow.LINK_TOGGLE,
                            title = if (viewModel.transportIsDirect) "Hermes Link" else "Live token streaming",
                            subtitle = if (viewModel.transportIsDirect)
                                "The API server behind Missions, Runs, Shipyard and the Gateway space — chat itself streams over the direct connection"
                            else "Stream replies over the gateway side-channel (SSE); falls back to Matrix sync when unreachable",
                            checked = sideChannelEnabled,
                            onCheckedChange = onSideChannelEnabledChanged,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = gatewayUrl,
                            onValueChange = onGatewayUrlChanged,
                            label = { Text("Gateway URL") },
                            placeholder = { Text("http://your-gateway-host:8642") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = sideChannelEnabled,
                        )
                        Spacer(Modifier.height(12.dp))
                        var keyVisible by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = gatewayApiKey,
                            onValueChange = onGatewayApiKeyChanged,
                            label = { Text("Gateway API key") },
                            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { keyVisible = !keyVisible }) {
                                    Text(if (keyVisible) "Hide" else "Show", fontSize = 12.sp)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = sideChannelEnabled,
                        )
                        Spacer(Modifier.height(10.dp))
                        // One-tap sanity check: probes the gateway's /health with the values above
                        // and toasts the result, so "why isn't it streaming" is never a mystery.
                        OutlinedButton(
                            onClick = onTestLink,
                            enabled = sideChannelEnabled,
                            shape = RoundedCornerShape(KeryxRadius.field),
                        ) {
                            Text("Test link", fontSize = 13.sp)
                        }
                    }

                    // --- Voice dictation ---
                    if (section == "Voice") SettingsCard("Voice Dictation", anchor = SettingsRow.VOICE_STT) {
                        Text(
                            "Adds a mic to the composer: record, transcribe, and the text lands in the " +
                                "input field. Works with any OpenAI-compatible transcription endpoint — " +
                                "a self-hosted server on your own network, OpenAI, Groq…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = sttUrl,
                            onValueChange = onSttUrlChanged,
                            label = { Text("STT server URL") },
                            placeholder = { Text("http://your-stt-host:8123") },
                            supportingText = { Text("Blank hides the mic. Bare host, /v1, or full /v1/audio/transcriptions path all work.", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(12.dp))
                        var sttKeyVisible by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = sttApiKey,
                            onValueChange = onSttApiKeyChanged,
                            label = { Text("API key (optional)") },
                            visualTransformation = if (sttKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { sttKeyVisible = !sttKeyVisible }) {
                                    Text(if (sttKeyVisible) "Hide" else "Show", fontSize = 12.sp)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = sttModel,
                            onValueChange = onSttModelChanged,
                            label = { Text("Model (optional)") },
                            placeholder = { Text("only if your provider requires one, e.g. whisper-1") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }

                    if (section == "Voice") SettingsCard("Voice Replies", anchor = SettingsRow.VOICE_TTS) {
                        Text(
                            "Reads agent replies aloud — long-press a message and tap the speaker. " +
                                "Works out of the box with this device's voice; point it at any " +
                                "OpenAI-compatible speech endpoint (Kokoro, openedai-speech, LocalAI…) " +
                                "for a custom voice.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        SettingsSwitchRow(
                            title = "Auto-speak replies",
                            subtitle = "Speak each finished reply in the open chat — tap the message's speaker to stop",
                            checked = ttsAutoSpeak,
                            onCheckedChange = onTtsAutoSpeakChanged,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = ttsUrl,
                            onValueChange = onTtsUrlChanged,
                            label = { Text("TTS server URL (optional)") },
                            placeholder = { Text("http://your-tts-host:8880") },
                            supportingText = { Text("Blank uses Android's built-in voice. Bare host, /v1, or full /v1/audio/speech path all work.", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(12.dp))
                        var ttsKeyVisible by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = ttsApiKey,
                            onValueChange = onTtsApiKeyChanged,
                            label = { Text("API key (optional)") },
                            visualTransformation = if (ttsKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { ttsKeyVisible = !ttsKeyVisible }) {
                                    Text(if (ttsKeyVisible) "Hide" else "Show", fontSize = 12.sp)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = ttsVoice,
                            onValueChange = onTtsVoiceChanged,
                            label = { Text("Voice (optional)") },
                            placeholder = { Text("e.g. alloy / af_sky") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = ttsModel,
                            onValueChange = onTtsModelChanged,
                            label = { Text("Model (optional)") },
                            placeholder = { Text("only if your provider requires one, e.g. tts-1") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }

                    // --- Message Appearance ---
                    if (section == "Appearance") SettingsCard("Look") {
                        val isDark by viewModel.isDarkTheme.collectAsState()
                        SettingsAnchor(SettingsRow.APPEARANCE_THEME) {
                            SettingsChoiceLabel("Theme")
                            KeryxSegmented(
                                options = listOf("system" to "System", "light" to "Light", "dark" to "Dark"),
                                selected = when (isDark) { null -> "system"; true -> "dark"; false -> "light" },
                                onSelect = { viewModel.toggleTheme(when (it) { "dark" -> true; "light" -> false; else -> null }) },
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        SettingsAnchor(SettingsRow.APPEARANCE_BUBBLES) {
                            SettingsChoiceLabel("Bubbles")
                            KeryxSegmented(
                                options = BubbleStyles.ALL.map { it to it },
                                selected = bubbleStyle,
                                onSelect = onBubbleStyleChanged,
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        SettingsAnchor(SettingsRow.APPEARANCE_TEXT) {
                            SettingsChoiceLabel("Text size")
                            val sizes = listOf("Small" to 0.85f, "Default" to 1.0f, "Large" to 1.2f)
                            KeryxSegmented(
                                options = sizes.map { it.first to it.first },
                                selected = sizes.firstOrNull { kotlin.math.abs(messageTextScale - it.second) < 0.01f }?.first ?: "Default",
                                onSelect = { pick -> sizes.firstOrNull { it.first == pick }?.let { onMessageTextScaleChanged(it.second) } },
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        SettingsAnchor(SettingsRow.APPEARANCE_ACCENT) {
                        Text("Accent Color", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        ColorPickerPanel(
                            current = currentAccentColor,
                            onColorSelected = onAccentColorChanged,
                            modifier = Modifier.fillMaxWidth(),
                            discSize = 180.dp,
                        )
                        Spacer(Modifier.height(18.dp))
                        Text("Accent 2", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            "The gradient partner: bubbles, the working cloud, and borders blend Accent → Accent 2.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        ColorPickerPanel(
                            current = currentAccentColor2,
                            onColorSelected = onAccentColor2Changed,
                            modifier = Modifier.fillMaxWidth(),
                            discSize = 140.dp,
                        )
                        Spacer(Modifier.height(12.dp))
                        // Live gradient preview: how Accent → Accent 2 will actually blend.
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(KeryxRadius.chip))
                                .background(
                                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                                        listOf(currentAccentColor.copy(alpha = 0.25f), currentAccentColor2.copy(alpha = 0.25f))
                                    )
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .align(Alignment.CenterHorizontally)
                        ) {
                            Text("Accent → Accent 2", color = currentAccentColor2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = onResetAppearance, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text("Reset to Default")
                        }
                    }

                    // --- Privacy & Security ---
                    if (section == "Privacy & Security") SettingsCard("Privacy & Security", anchor = SettingsRow.PRIVACY_LOCK) {
                        SettingsSwitchRow(
                            anchor = SettingsRow.PRIVACY_LOCK,
                            title = "Biometric App Lock",
                            subtitle = "Require FaceID/Fingerprint to open Keryx",
                            checked = biometricLockEnabled,
                            onCheckedChange = onBiometricLockChanged
                        )
                        // E2EE is a Matrix room property; the direct door has no rooms to encrypt.
                        if (!viewModel.transportIsDirect) {
                            Spacer(Modifier.height(8.dp))
                            SettingsSwitchRow(
                                anchor = SettingsRow.PRIVACY_E2EE,
                                title = "End-to-End Encryption",
                                subtitle = "Enable Matrix E2EE session management",
                                checked = e2eeEnabled,
                                onCheckedChange = onE2eeChanged
                            )
                        }
                    }

                    // Senses owns its own preferences file and needs nothing from this screen, so
                    // it sits next to Privacy rather than taking thirty parameters of plumbing.
                    if (section == "Privacy & Security") SettingsAnchor(SettingsRow.PRIVACY_SENSES) { chat.keryx.app.senses.SensesSettingsCard() }

                    // --- Interface ---
                    if (section == "Appearance") SettingsCard("Feel") {
                        SettingsSwitchRow(
                            anchor = SettingsRow.APPEARANCE_HAPTICS,
                            title = "Haptic Feedback",
                            subtitle = "Vibrate on interactions",
                            checked = hapticsEnabled,
                            onCheckedChange = onHapticsChanged
                        )
                        Spacer(Modifier.height(16.dp))
                        SettingsAnchor(SettingsRow.APPEARANCE_LOADING) {
                            SettingsChoiceLabel("Loading animation")
                            KeryxSegmented(
                                options = listOf(
                                    "Caduceus" to "☤ Caduceus",
                                    "Braille" to "⠋ Braille",
                                    "Dots" to "○ Dots",
                                    "ASCII Wave" to "▅ Wave",
                                    "Ember Drift" to "✦ Ember",
                                ),
                                selected = animationStyle,
                                onSelect = onAnimationStyleChanged,
                            )
                        }
                    }

                    // --- Sessions (direct door): what left the list, and the sweep ---
                    if (section == "Sessions") SettingsCard("Archived", anchor = SettingsRow.SESSIONS_ARCHIVED) {
                        val archived by viewModel.archivedRooms.collectAsState()
                        LaunchedEffect(Unit) { viewModel.loadArchivedSessions() }
                        if (archived.isEmpty()) Text(
                            "Nothing archived. Long-press a session in the drawer to put it away — it stays on the gateway.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        archived.forEach { room ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        viewModel.selectRoom(room)
                                        onDismissRequest()
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(room.name, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    if (room.preview.isNotBlank()) Text(room.preview, fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                }
                                TextButton(onClick = { viewModel.unarchiveSession(room.id) }) {
                                    Text("Restore", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    if (section == "Sessions") SettingsCard("Pruning", anchor = SettingsRow.SESSIONS_PRUNE) {
                        var pruneOpen by remember { mutableStateOf(false) }
                        Text(
                            "Delete old, idle sessions from the gateway in one sweep — previewed before anything goes.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { pruneOpen = true }, shape = RoundedCornerShape(KeryxRadius.field)) {
                            Text("Prune sessions…", fontSize = 13.sp)
                        }
                        if (pruneOpen) SessionPruneDialog(viewModel = viewModel, onDismiss = { pruneOpen = false })
                    }

                    if (section == "About") SettingsCard("Diagnostics", anchor = SettingsRow.ABOUT_CRASH) {
                        val diagContext = androidx.compose.ui.platform.LocalContext.current
                        var crashText by remember { mutableStateOf(chat.keryx.app.CrashLog.read(diagContext)) }
                        Text(
                            text = if (crashText.isBlank()) "No crashes recorded"
                                else "Crash log: ${crashText.length / 1024} KB recorded",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = "Kept only on this device; share it when reporting a bug.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { chat.keryx.app.CrashLog.share(diagContext) },
                                enabled = crashText.isNotBlank(),
                                shape = RoundedCornerShape(KeryxRadius.field),
                            ) { Text("Share", fontSize = 13.sp) }
                            OutlinedButton(
                                onClick = {
                                    chat.keryx.app.CrashLog.clear(diagContext)
                                    crashText = ""
                                },
                                enabled = crashText.isNotBlank(),
                                shape = RoundedCornerShape(KeryxRadius.field),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            ) { Text("Clear", fontSize = 13.sp) }
                        }
                    }
                    if (section == "About") SettingsCard("Keryx", anchor = SettingsRow.ABOUT_VERSION) {
                        val aboutContext = androidx.compose.ui.platform.LocalContext.current
                        val lines = listOfNotNull(
                            "App" to "Keryx v${chat.keryx.app.BuildConfig.VERSION_NAME} (${chat.keryx.app.BuildConfig.VERSION_CODE})",
                            "Door" to (if (viewModel.transportIsDirect) "Direct to gateway" else "Matrix"),
                            hubHealth.data?.version?.takeIf { it.isNotBlank() }?.let { "Gateway" to "hermes-agent $it" },
                            caps?.model?.takeIf { it.isNotBlank() }?.let { "Brain" to it },
                            "Android" to "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
                        )
                        lines.forEach { (k, v) ->
                            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                                Text(k, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(86.dp))
                                Text(v, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        // The Desktop's "Copy error details": versions, door, brain — the lines a
                        // bug report needs and a person never remembers. No secrets ride along.
                        OutlinedButton(
                            onClick = {
                                val text = lines.joinToString("\n") { (k, v) -> "$k: $v" }
                                aboutContext.getSystemService(android.content.ClipboardManager::class.java)
                                    ?.setPrimaryClip(android.content.ClipData.newPlainText("Keryx diagnostics", text))
                                android.widget.Toast.makeText(aboutContext, "Diagnostics copied", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(KeryxRadius.field),
                        ) { Text("Copy diagnostics", fontSize = 13.sp) }
                    }

                    Spacer(Modifier.height(40.dp))
                }
                }
            }
          }
        }
}

/**
 * Settings as a place (2.0 Phase 4). Collects everything [SettingsScreen] needs straight from
 * the ViewModel, so the navigation host can open Settings like any other destination and the
 * drawer stops hauling thirty parameters of plumbing it never looked at.
 */
@Composable
fun SettingsPlace(viewModel: ChatViewModel, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val currentUserId by viewModel.currentUserId.collectAsState()
    val currentAccent by viewModel.accentColor.collectAsState()
    val currentAccent2 by viewModel.accentColor2.collectAsState()
    val matrixUrl by viewModel.matrixUrl.collectAsState()
    val agentMatrixId by viewModel.agentMatrixId.collectAsState()
    val matrixToken by viewModel.matrixToken.collectAsState()
    val biometricLockEnabled by viewModel.biometricLock.collectAsState()
    val e2eeEnabled by viewModel.e2eeEnabled.collectAsState()
    val hapticsEnabled by viewModel.hapticsEnabled.collectAsState()
    val animationStyle by viewModel.animationStyle.collectAsState()
    val bubbleStyle by viewModel.bubbleStyle.collectAsState()
    val messageTextScale by viewModel.messageTextScale.collectAsState()
    val allowInsecure by viewModel.allowInsecure.collectAsState()
    val gatewayUrl by viewModel.gatewayUrl.collectAsState()
    val gatewayApiKey by viewModel.gatewayApiKey.collectAsState()
    val sideChannelEnabled by viewModel.sideChannelEnabled.collectAsState()
    val sttUrl by viewModel.voice.sttUrl.collectAsState()
    val sttApiKey by viewModel.voice.sttApiKey.collectAsState()
    val sttModel by viewModel.voice.sttModel.collectAsState()
    val ttsAutoSpeak by viewModel.voice.ttsAutoSpeak.collectAsState()
    val ttsUrl by viewModel.voice.ttsUrl.collectAsState()
    val ttsApiKey by viewModel.voice.ttsApiKey.collectAsState()
    val ttsVoice by viewModel.voice.ttsVoice.collectAsState()
    val ttsModel by viewModel.voice.ttsModel.collectAsState()
    val showTelemetry by viewModel.showTelemetry.collectAsState()
    val missionAlertsEnabled by viewModel.missions.alertsEnabled.collectAsState()
    val pushEnabled by viewModel.pushEnabled.collectAsState()
    val pushGatewayUrl by viewModel.pushGatewayUrl.collectAsState()

    SettingsScreen(
        viewModel = viewModel,
        currentAccentColor = currentAccent,
        onAccentColorChanged = { viewModel.setAccentColor(it) },
        currentAccentColor2 = currentAccent2,
        onAccentColor2Changed = { viewModel.setAccentColor2(it) },
        currentUserId = currentUserId,
        matrixUrl = matrixUrl,
        onMatrixUrlChanged = { viewModel.setMatrixUrl(it) },
        agentMatrixId = agentMatrixId,
        onAgentMatrixIdChanged = { viewModel.setAgentMatrixId(it) },
        matrixToken = matrixToken,
        onMatrixTokenChanged = { viewModel.setMatrixToken(it) },
        allowInsecure = allowInsecure,
        onAllowInsecureChanged = { viewModel.setAllowInsecure(it) },
        gatewayUrl = gatewayUrl,
        onGatewayUrlChanged = { viewModel.setGatewayUrl(it) },
        gatewayApiKey = gatewayApiKey,
        onGatewayApiKeyChanged = { viewModel.setGatewayApiKey(it) },
        sideChannelEnabled = sideChannelEnabled,
        onSideChannelEnabledChanged = { viewModel.setSideChannelEnabled(it) },
        sttUrl = sttUrl,
        onSttUrlChanged = { viewModel.voice.setSttUrl(it) },
        sttApiKey = sttApiKey,
        onSttApiKeyChanged = { viewModel.voice.setSttApiKey(it) },
        sttModel = sttModel,
        onSttModelChanged = { viewModel.voice.setSttModel(it) },
        ttsAutoSpeak = ttsAutoSpeak,
        onTtsAutoSpeakChanged = { viewModel.voice.setTtsAutoSpeak(it) },
        ttsUrl = ttsUrl,
        onTtsUrlChanged = { viewModel.voice.setTtsUrl(it) },
        ttsApiKey = ttsApiKey,
        onTtsApiKeyChanged = { viewModel.voice.setTtsApiKey(it) },
        ttsVoice = ttsVoice,
        onTtsVoiceChanged = { viewModel.voice.setTtsVoice(it) },
        ttsModel = ttsModel,
        onTtsModelChanged = { viewModel.voice.setTtsModel(it) },
        onTestLink = { viewModel.testGatewayLink() },
        showTelemetry = showTelemetry,
        onShowTelemetryChanged = { viewModel.setShowTelemetry(it) },
        missionAlertsEnabled = missionAlertsEnabled,
        onMissionAlertsChanged = {
            viewModel.missions.setAlertsEnabled(it)
            chat.keryx.app.notify.MissionAlertsWorker.setEnabled(context, it)
        },
        pushEnabled = pushEnabled,
        onPushEnabledChanged = { on ->
            if (on) {
                // Enable BEFORE latching: built-in mode needs pushEnabled=true when the
                // endpoint registration flows back through onNewEndpoint.
                viewModel.setPushEnabled(true)
                when (chat.keryx.app.notify.PushManager.enable(context)) {
                    chat.keryx.app.notify.PushManager.EnableResult.NoGateway -> {
                        // Don't latch a switch that can't deliver — say why instead.
                        viewModel.setPushEnabled(false)
                        viewModel.toast("Set the push gateway URL first (your ntfy server) — staying on in-app sync")
                    }
                    chat.keryx.app.notify.PushManager.EnableResult.BuiltinActive ->
                        viewModel.toast("Push active — Keryx holds its own connection, no distributor app needed")
                    chat.keryx.app.notify.PushManager.EnableResult.Requested -> Unit
                }
            } else {
                viewModel.setPushEnabled(false)
                chat.keryx.app.notify.PushManager.disable(context)
            }
        },
        pushGatewayUrl = pushGatewayUrl,
        onPushGatewayUrlChanged = { viewModel.setPushGatewayUrl(it) },
        biometricLockEnabled = biometricLockEnabled,
        onBiometricLockChanged = { viewModel.setBiometricLock(it) },
        e2eeEnabled = e2eeEnabled,
        onE2eeChanged = { viewModel.setE2eeEnabled(it) },
        hapticsEnabled = hapticsEnabled,
        onHapticsChanged = { viewModel.setHapticsEnabled(it) },
        animationStyle = animationStyle,
        onAnimationStyleChanged = { viewModel.setAnimationStyle(it) },
        bubbleStyle = bubbleStyle,
        onBubbleStyleChanged = { viewModel.setBubbleStyle(it) },
        messageTextScale = messageTextScale,
        onMessageTextScaleChanged = { viewModel.setMessageTextScale(it) },
        onResetAppearance = { viewModel.resetMessageAppearance() },
        onLoginRequested = { user, pass, callback ->
            viewModel.loginToMatrix(user, pass, callback)
        },
        onLogout = {
            onClose()
            // Remove the pusher while the session can still authenticate the request.
            if (pushEnabled) chat.keryx.app.notify.PushManager.disable(context)
            viewModel.setPushEnabled(false)
            viewModel.logout()
        },
        onDismissRequest = onClose,
    )
}
