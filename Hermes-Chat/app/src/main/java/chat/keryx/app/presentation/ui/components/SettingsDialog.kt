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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import chat.keryx.app.R
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
            var query by remember { mutableStateOf("") }

            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                TopAppBar(
                    title = {
                        Text(
                            (settingsSectionLabel(section)?.let { stringResource(it) } ?: section ?: stringResource(R.string.settings_title)).uppercase(),
                            fontSize = KeryxType.headline,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 5.sp,
                        )
                    },
                    navigationIcon = {
                        if (section != null) {
                            IconButton(onClick = { section = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                            }
                        } else {
                            IconButton(onClick = onDismissRequest) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.settings_close))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )

                // Makeover: the hub and its pages slide like places, not swap like tabs — in
                // from the right going deeper, back out to the right coming home, on the app's
                // own springs. Each page keeps its own scroll, so coming back lands where you left.
                val reduced by rememberReducedMotion()
                androidx.compose.animation.AnimatedContent(
                    targetState = section,
                    transitionSpec = { settingsPageTransition(deeper = targetState != null, reduced = reduced) },
                    label = "settingsPage",
                ) { shown ->
                val scrollState = rememberScrollState()
                var contentTop by remember { mutableStateOf(0f) }
                val settingsFocus = remember(focusId, scrollState) {
                    SettingsFocus(target = focusId, scroll = scrollState, contentTop = { contentTop }) { focusId = null }
                }
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

                    if (shown == null) {
                        Spacer(Modifier.height(4.dp))
                        // Search (2.10): every row in the app, by name or by the words you'd
                        // use for it; a hit opens its section scrolled to the row, lit for a
                        // beat. The Desktop's settings search, without the schema.
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(stringResource(R.string.settings_search_hint)) },
                            leadingIcon = { Icon(KeryxGlyphs.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            trailingIcon = if (query.isNotBlank()) {
                                { TextButton(onClick = { query = "" }) { Text(stringResource(R.string.settings_clear), fontSize = KeryxType.micro) } }
                            } else null,
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        )
                        val shownTitles = SettingsRow.entries.associateWith { stringResource(it.label) }
                        val hits = remember(query, direct, shownTitles) { SettingsCatalog.search(query, direct) { shownTitles.getValue(it) } }
                        if (query.isNotBlank()) {
                            if (hits.isEmpty()) Text(
                                stringResource(R.string.settings_search_none),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = KeryxType.body,
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
                                        Text(stringResource(e.label), fontSize = KeryxType.title, fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface)
                                        Text(settingsSectionLabel(e.section(direct))?.let { stringResource(it) } ?: e.section(direct).orEmpty(), fontSize = KeryxType.caption,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(KeryxGlyphs.ChevronRight, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(18.dp))
                                }
                            }
                        } else {
                        // Makeover: the rows sit in labelled groups, the Hermes Mobile way — a
                        // map of the app, not nine equal tiles. Each subtitle says what is SET
                        // ("Gilded bubbles · Constellation"), not what the page is about.
                        KeryxHubGroup(stringResource(R.string.settings_group_connection)) {
                            KeryxHubRow(Icons.Default.Person, stringResource(R.string.settings_section_account),
                                currentUserId ?: stringResource(R.string.settings_not_signed_in)) { section = "Account" }
                            KeryxHubDivider()
                            // One wire, one row (2.10): on the direct door the gateway IS the
                            // connection and Hermes Link is its API server, so they share a page.
                            // Matrix keeps two — the homeserver is one thing, the link another.
                            if (direct) {
                                val fleet by viewModel.doors.fleet.collectAsState()
                                val active = fleet.active
                                val where = active?.name
                                    ?: viewModel.directGatewayUrl.ifBlank { stringResource(R.string.settings_hub_gateway_blank) }
                                        .removePrefix("https://").removePrefix("http://")
                                KeryxHubRow(Icons.Default.Dns, stringResource(R.string.settings_row_connection_gateway),
                                    if (sideChannelEnabled) stringResource(R.string.settings_hub_gateway_linked, where) else where,
                                    trailing = if (fleet.hasChoice) fleet.size.toString() else null) { section = "Gateway" }
                            }
                            else {
                                KeryxHubRow(Icons.Default.Dns, stringResource(R.string.settings_section_connection),
                                    matrixUrl.ifBlank { stringResource(R.string.settings_hub_connection_blank) }) { section = "Connection" }
                                KeryxHubDivider()
                                KeryxHubRow(Icons.Default.Bolt, stringResource(R.string.settings_section_hermes_link),
                                    if (sideChannelEnabled) stringResource(R.string.settings_link_streaming) else stringResource(R.string.settings_off),
                                    trailing = if (sideChannelEnabled) stringResource(R.string.settings_on) else null) { section = "Hermes Link" }
                            }
                        }
                        KeryxHubGroup(stringResource(R.string.settings_group_agent)) {
                            KeryxHubRow(Icons.Default.Memory, stringResource(R.string.settings_section_agent),
                                caps?.model?.takeIf { it.isNotBlank() } ?: stringResource(R.string.settings_hub_agent_blank)) { section = "Agent" }
                            KeryxHubDivider()
                            KeryxHubRow(Icons.Default.Pets, stringResource(R.string.settings_section_companion),
                                petInfo?.displayName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.settings_hub_companion_blank)) { section = "Companion" }
                            KeryxHubDivider()
                            KeryxHubRow(Icons.Default.Mic, stringResource(R.string.settings_section_voice),
                                listOfNotNull(
                                    if (sttUrl.isNotBlank()) stringResource(R.string.settings_hub_voice_dictation) else null,
                                    if (ttsAutoSpeak) stringResource(R.string.settings_hub_voice_autospeak) else null,
                                // No STT server = no mic, but spoken replies still work on this
                                // phone's own voice — so never just "Off".
                                ).ifEmpty { listOf(stringResource(R.string.settings_hub_voice_default)) }.joinToString(" · ")) { section = "Voice" }
                        }
                        KeryxHubGroup(stringResource(R.string.settings_group_app)) {
                            KeryxHubRow(Icons.Default.Palette, stringResource(R.string.settings_section_appearance),
                                stringResource(R.string.settings_hub_appearance, bubbleStyle, animationStyle.removeSuffix(" Drift"))) { section = "Appearance" }
                            KeryxHubDivider()
                            KeryxHubRow(Icons.Default.Lock, stringResource(R.string.settings_section_privacy),
                                listOfNotNull(
                                    if (biometricLockEnabled) stringResource(R.string.settings_hub_lock_on) else stringResource(R.string.settings_hub_lock_off),
                                    if (e2eeEnabled && !direct) "E2EE" else null,
                                ).joinToString(" · ")) { section = "Privacy & Security" }
                            if (direct) {
                                val archived by viewModel.sessions.archivedRooms.collectAsState()
                                KeryxHubDivider()
                                KeryxHubRow(KeryxGlyphs.Stack, stringResource(R.string.settings_section_sessions),
                                    stringResource(R.string.settings_hub_sessions),
                                    trailing = archived.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.settings_hub_archived, it, it) }) { section = "Sessions" }
                            }
                        }
                        KeryxHubGroup {
                            KeryxHubRow(Icons.Default.BugReport, stringResource(R.string.settings_section_about),
                                "Keryx v${chat.keryx.app.BuildConfig.VERSION_NAME}" +
                                    (hubHealth.data?.version?.takeIf { it.isNotBlank() }?.let { " · hermes $it" } ?: "")) { section = "About" }
                        }
                        }
                    }

                    // --- Account ---
                    // The demo (3.0 first run) has no account and no door to switch: just the way out.
                    if (shown == "Account" && viewModel.isDemo) SettingsCard(stringResource(R.string.settings_section_account), anchor = SettingsRow.ACCOUNT_IDENTITY) {
                        val demoContext = androidx.compose.ui.platform.LocalContext.current
                        Text(
                            text = stringResource(R.string.settings_demo),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = KeryxType.title,
                        )
                        Text(
                            text = stringResource(R.string.settings_demo_blurb),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = KeryxType.caption,
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = {
                            viewModel.doors.leaveDemo()
                            relaunchApp(demoContext)
                        }) { Text(stringResource(R.string.settings_demo_connect)) }
                    }
                    if (shown == "Account" && !viewModel.isDemo) SettingsCard(stringResource(R.string.settings_section_account), anchor = SettingsRow.ACCOUNT_IDENTITY) {
                        Text(
                            text = currentUserId ?: stringResource(R.string.settings_not_signed_in),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = KeryxType.title,
                        )
                        Text(
                            text = if (viewModel.transportIsDirect) viewModel.directGatewayUrl.ifBlank { stringResource(R.string.settings_no_gateway_set) }
                            else matrixUrl.ifBlank { stringResource(R.string.settings_no_homeserver_set) },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = KeryxType.caption,
                        )
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(
                            onClick = onLogout,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_sign_out))
                        }
                    }

                    // --- Transport: the two doors, toggleable without touching either session ---
                    // (Phase 4's second door, made a switch: the Matrix session stays in
                    // Trixnity's store, the sealed direct token stays in prefs — flipping the
                    // door just decides which spine the next process life boots.)
                    if (shown == "Account" && !viewModel.isDemo) SettingsCard(stringResource(R.string.settings_row_account_transport), anchor = SettingsRow.ACCOUNT_TRANSPORT) {
                        val direct = viewModel.transportIsDirect
                        val otherReady =
                            if (direct) viewModel.doors.matrixSessionOnFile else viewModel.doors.directCredentialsOnFile
                        var confirmSwitch by remember { mutableStateOf(false) }
                        val settingsContext = androidx.compose.ui.platform.LocalContext.current
                        Text(
                            text = if (direct) stringResource(R.string.settings_door_direct) else "Matrix",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = KeryxType.title,
                        )
                        Text(
                            text = if (direct) stringResource(R.string.settings_door_direct_blurb) else stringResource(R.string.settings_door_matrix_blurb),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = KeryxType.caption,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = when {
                                direct && otherReady -> stringResource(R.string.settings_switch_matrix_ready)
                                direct -> stringResource(R.string.settings_switch_matrix_fresh)
                                otherReady -> stringResource(R.string.settings_switch_direct_ready)
                                else -> stringResource(R.string.settings_switch_direct_fresh)
                            },
                            color = chat.keryx.app.theme.quietInk(alpha = 0.8f),
                            fontSize = KeryxType.caption,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { confirmSwitch = true }) {
                            Text(if (direct) stringResource(R.string.settings_switch_to_matrix) else stringResource(R.string.settings_switch_to_direct))
                        }
                        if (confirmSwitch) {
                            AlertDialog(
                                onDismissRequest = { confirmSwitch = false },
                                title = { Text(if (direct) stringResource(R.string.settings_switch_to_matrix_q) else stringResource(R.string.settings_switch_to_direct_q)) },
                                text = {
                                    Text(stringResource(R.string.settings_switch_body))
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        viewModel.doors.switchTransport(if (direct) "matrix" else "direct")
                                        relaunchApp(settingsContext)
                                    }) { Text(stringResource(R.string.settings_switch_restart)) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { confirmSwitch = false }) { Text(stringResource(R.string.mk_cancel)) }
                                },
                            )
                        }
                    }

                    // --- Agent (gateway-backed: what's running, and how it speaks up) ---
                    if (shown == "Agent") {
                        LaunchedEffect(Unit) {
                            viewModel.hub.refreshReasoningCaps()
                            viewModel.hub.refreshHealth()
                        }
                        SettingsCard(stringResource(R.string.settings_card_brain)) {
                            val rows = listOfNotNull(
                                caps?.model?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.settings_brain_model) to it },
                                caps?.let {
                                    stringResource(R.string.settings_brain_reasoning) to chat.keryx.core.model.ReasoningEffort.label(it.current, it.labels).ifBlank { "—" }
                                },
                                hubHealth.data?.version?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.settings_section_gateway) to "hermes-agent $it" },
                                hubHealth.data?.gatewayState?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.settings_brain_state) to it },
                            )
                            if (rows.isEmpty()) {
                                Text(
                                    hubHealth.error ?: stringResource(R.string.settings_brain_probing),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = KeryxType.caption,
                                )
                            } else rows.forEach { (k, v) ->
                                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                                    Text(k, fontSize = KeryxType.body, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(86.dp))
                                    Text(v, fontSize = KeryxType.body, fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.settings_brain_console),
                                color = chat.keryx.app.theme.quietInk(alpha = 0.7f),
                                fontSize = KeryxType.micro,
                            )
                        }
                        SettingsCard(stringResource(R.string.settings_card_presence)) {
                            SettingsSwitchRow(
                                anchor = SettingsRow.AGENT_TELEMETRY,
                                title = stringResource(R.string.settings_row_agent_telemetry),
                                subtitle = stringResource(R.string.settings_telemetry_sub),
                                checked = showTelemetry,
                                onCheckedChange = onShowTelemetryChanged,
                            )
                            SettingsSwitchRow(
                                anchor = SettingsRow.AGENT_ALERTS,
                                title = stringResource(R.string.settings_row_agent_alerts),
                                subtitle = stringResource(R.string.settings_alerts_sub),
                                checked = missionAlertsEnabled,
                                onCheckedChange = onMissionAlertsChanged,
                            )
                            val resume by viewModel.resumeLastRoom.collectAsState()
                            SettingsSwitchRow(
                                anchor = SettingsRow.AGENT_RESUME,
                                title = stringResource(R.string.settings_row_agent_resume),
                                subtitle = stringResource(R.string.settings_resume_sub),
                                checked = resume,
                                onCheckedChange = { viewModel.setResumeLastRoom(it) },
                            )
                            val starters by viewModel.starters.starterSuggestions.collectAsState()
                            SettingsSwitchRow(
                                anchor = SettingsRow.AGENT_STARTERS,
                                title = stringResource(R.string.settings_row_agent_starters),
                                subtitle = stringResource(R.string.settings_starters_sub),
                                checked = starters,
                                onCheckedChange = { viewModel.starters.setStarterSuggestions(it) },
                            )
                            if (starters) {
                                val aiStarters by viewModel.starters.starterAiPrompts.collectAsState()
                                SettingsSwitchRow(
                                    title = stringResource(R.string.settings_starters_ai),
                                    subtitle = stringResource(R.string.settings_starters_ai_sub),
                                    checked = aiStarters,
                                    onCheckedChange = { viewModel.starters.setStarterAiPrompts(it) },
                                )
                                val reminders by viewModel.starters.starterReminders.collectAsState()
                                SettingsSwitchRow(
                                    title = stringResource(R.string.settings_starters_reminders),
                                    subtitle = stringResource(R.string.settings_starters_reminders_sub),
                                    checked = reminders,
                                    onCheckedChange = { viewModel.starters.setStarterReminders(it) },
                                )
                            }
                            if (direct) {
                                val sticky by viewModel.sessions.stickyModel.collectAsState()
                                SettingsSwitchRow(
                                    anchor = SettingsRow.AGENT_STICKY_MODEL,
                                    title = stringResource(R.string.settings_row_agent_sticky_model),
                                    subtitle = stringResource(R.string.settings_sticky_sub),
                                    checked = sticky,
                                    onCheckedChange = { viewModel.sessions.setStickyModel(it) },
                                )
                            }
                        }
                    }

                    // --- Companion (the petdex mascot) ---
                    if (shown == "Companion") SettingsCard(stringResource(R.string.settings_section_companion), anchor = SettingsRow.COMPANION_PICK) {
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
                                        fontWeight = FontWeight.SemiBold, fontSize = KeryxType.title,
                                        color = MaterialTheme.colorScheme.onSurface)
                                    Text(stringResource(R.string.settings_companion_lives),
                                        fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } else {
                            Text(
                                stringResource(R.string.settings_companion_none),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = KeryxType.caption,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { showPetPicker = true }) {
                            Text(if (petInfo != null) stringResource(R.string.settings_companion_change) else stringResource(R.string.settings_companion_choose))
                        }
                    }

                    // --- Connection ---
                    // Direct door: no homeserver, no agent ids, no push gateway — the gateway IS
                    // the connection. What remains is where it lives (set at sign-in; changed by
                    // signing out) and the one switch that applies to it.
                    // The fleet (2.11): the Desktop's Settings → Gateways registry. One card holds
                    // every gateway the phone can reach; the row that used to say "sign out and
                    // connect again" is now "Add gateway".
                    if (shown == "Gateway") SettingsCard(stringResource(R.string.settings_row_connection_gateway), anchor = SettingsRow.CONNECTION_GATEWAY) {
                        val settingsContext = androidx.compose.ui.platform.LocalContext.current
                        GatewayRegistry(viewModel, onRelaunch = { relaunchApp(settingsContext) })
                        val fleet by viewModel.doors.fleet.collectAsState()
                        if (fleet.hasChoice) {
                            Spacer(Modifier.height(8.dp))
                            SettingsSwitchRow(
                                anchor = SettingsRow.CONNECTION_FLEET_RESUME,
                                title = stringResource(R.string.settings_row_connection_fleet_resume),
                                subtitle = if (fleet.resumeLastGateway) stringResource(R.string.settings_fleet_resume_on)
                                else stringResource(R.string.settings_fleet_resume_off),
                                checked = fleet.resumeLastGateway,
                                onCheckedChange = viewModel.doors::setResumeLastGateway,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        SettingsSwitchRow(
                            anchor = SettingsRow.CONNECTION_INSECURE,
                            title = stringResource(R.string.settings_row_connection_insecure),
                            subtitle = stringResource(R.string.settings_insecure_gateways),
                            checked = allowInsecure,
                            onCheckedChange = onAllowInsecureChanged,
                        )
                    }
                    if (shown == "Connection") SettingsCard(stringResource(R.string.settings_section_connection), anchor = SettingsRow.CONNECTION_HOMESERVER) {
                        OutlinedTextField(
                            value = matrixUrl,
                            onValueChange = onMatrixUrlChanged,
                            label = { Text(stringResource(R.string.settings_row_connection_homeserver)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = agentMatrixId,
                            onValueChange = onAgentMatrixIdChanged,
                            label = { Text(stringResource(R.string.settings_row_connection_heralds)) },
                            placeholder = { Text("@hermes:example.com, @scout:example.com") },
                            supportingText = {
                                Text(stringResource(R.string.settings_heralds_help))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        SettingsAnchor(SettingsRow.CONNECTION_HERALDS) {
                        HeraldsList(
                            agentMatrixId = agentMatrixId,
                            overrides = viewModel.prefs.heraldAccents.collectAsState().value,
                            onSetAccent = { key, hex -> viewModel.prefs.setHeraldAccent(key, hex) },
                        )
                        }
                        Spacer(Modifier.height(8.dp))
                        SettingsSwitchRow(
                            anchor = SettingsRow.CONNECTION_INSECURE,
                            title = stringResource(R.string.settings_row_connection_insecure),
                            subtitle = stringResource(R.string.settings_insecure_servers),
                            checked = allowInsecure,
                            onCheckedChange = onAllowInsecureChanged,
                        )
                        SettingsSwitchRow(
                            anchor = SettingsRow.CONNECTION_PUSH,
                            title = stringResource(R.string.settings_row_connection_push),
                            subtitle = when {
                                !pushEnabled -> stringResource(R.string.settings_push_off)
                                org.unifiedpush.android.connector.UnifiedPush
                                    .getDistributors(androidx.compose.ui.platform.LocalContext.current)
                                    .isNotEmpty() -> stringResource(R.string.settings_push_distributor)
                                else -> stringResource(R.string.settings_push_builtin)
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
                                label = { Text(stringResource(R.string.settings_push_url)) },
                                placeholder = { Text("https://ntfy.example.com") },
                                supportingText = {
                                    Text(stringResource(R.string.settings_push_url_help), fontSize = KeryxType.micro)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }

                        var reauth by remember { mutableStateOf(false) }
                        SettingsAnchor(SettingsRow.CONNECTION_REAUTH) {
                        TextButton(onClick = { reauth = !reauth }) {
                            Text(if (reauth) stringResource(R.string.settings_reauth_hide) else stringResource(R.string.settings_row_connection_reauth))
                        }
                        }
                        if (reauth) {
                            var usernameInput by remember { mutableStateOf("") }
                            var passwordInput by remember { mutableStateOf("") }
                            var passwordVisible by remember { mutableStateOf(false) }
                            var testStatus by remember { mutableStateOf("") }
                            val needCreds = stringResource(R.string.settings_login_need_creds)
                            val authenticating = stringResource(R.string.settings_login_authenticating)
                            val loginOk = stringResource(R.string.settings_login_ok)
                            val loginFailed = stringResource(R.string.settings_reauth_failed)
                            OutlinedTextField(
                                value = usernameInput,
                                onValueChange = { usernameInput = it },
                                label = { Text(stringResource(R.string.settings_username)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text(stringResource(R.string.settings_password)) },
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Text(if (passwordVisible) stringResource(R.string.settings_hide) else stringResource(R.string.settings_show), fontSize = KeryxType.caption)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    if (usernameInput.isBlank() || passwordInput.isBlank()) {
                                        testStatus = needCreds
                                    } else {
                                        testStatus = authenticating
                                        onLoginRequested(usernameInput, passwordInput) { success, msg ->
                                            testStatus = msg ?: if (success) loginOk else loginFailed
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.settings_login)) }
                            if (testStatus.isNotEmpty()) {
                                Text(testStatus, color = MaterialTheme.colorScheme.primary, fontSize = KeryxType.caption, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }

                    // --- Hermes Link (side-channel streaming) ---
                    if (shown == "Hermes Link" || shown == "Gateway") SettingsCard(stringResource(R.string.settings_section_hermes_link), anchor = SettingsRow.LINK_URL) {
                        // The same link serves two jobs by door. On Matrix it is the SSE
                        // side-channel that streams tokens ahead of sync. On the direct door the
                        // websocket already streams; the link is the API server the spaces ride
                        // (Missions, Runs, Shipyard, the Gateway space, the pet).
                        SettingsSwitchRow(
                            anchor = SettingsRow.LINK_TOGGLE,
                            title = if (viewModel.transportIsDirect) stringResource(R.string.settings_section_hermes_link) else stringResource(R.string.settings_link_streaming),
                            subtitle = if (viewModel.transportIsDirect)
                                stringResource(R.string.settings_link_direct_sub)
                            else stringResource(R.string.settings_link_matrix_sub),
                            checked = sideChannelEnabled,
                            onCheckedChange = onSideChannelEnabledChanged,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = gatewayUrl,
                            onValueChange = onGatewayUrlChanged,
                            label = { Text(stringResource(R.string.settings_row_link_url)) },
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
                            label = { Text(stringResource(R.string.settings_gateway_api_key)) },
                            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { keyVisible = !keyVisible }) {
                                    Text(if (keyVisible) stringResource(R.string.settings_hide) else stringResource(R.string.settings_show), fontSize = KeryxType.caption)
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
                            Text(stringResource(R.string.settings_test_link), fontSize = KeryxType.body)
                        }
                    }

                    // --- Voice dictation ---
                    if (shown == "Voice") SettingsCard(stringResource(R.string.settings_card_dictation), anchor = SettingsRow.VOICE_STT) {
                        Text(
                            stringResource(R.string.settings_stt_blurb),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = KeryxType.caption,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = sttUrl,
                            onValueChange = onSttUrlChanged,
                            label = { Text(stringResource(R.string.settings_stt_url)) },
                            placeholder = { Text("http://your-stt-host:8123") },
                            supportingText = { Text(stringResource(R.string.settings_stt_url_help), fontSize = KeryxType.micro) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(12.dp))
                        var sttKeyVisible by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = sttApiKey,
                            onValueChange = onSttApiKeyChanged,
                            label = { Text(stringResource(R.string.settings_api_key_optional)) },
                            visualTransformation = if (sttKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { sttKeyVisible = !sttKeyVisible }) {
                                    Text(if (sttKeyVisible) stringResource(R.string.settings_hide) else stringResource(R.string.settings_show), fontSize = KeryxType.caption)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = sttModel,
                            onValueChange = onSttModelChanged,
                            label = { Text(stringResource(R.string.settings_model_optional)) },
                            placeholder = { Text(stringResource(R.string.settings_model_hint, "whisper-1")) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }

                    if (shown == "Voice") SettingsCard(stringResource(R.string.settings_card_replies), anchor = SettingsRow.VOICE_TTS) {
                        Text(
                            stringResource(R.string.settings_tts_blurb),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = KeryxType.caption,
                        )
                        Spacer(Modifier.height(4.dp))
                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_tts_autospeak),
                            subtitle = stringResource(R.string.settings_tts_autospeak_sub),
                            checked = ttsAutoSpeak,
                            onCheckedChange = onTtsAutoSpeakChanged,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = ttsUrl,
                            onValueChange = onTtsUrlChanged,
                            label = { Text(stringResource(R.string.settings_tts_url)) },
                            placeholder = { Text("http://your-tts-host:8880") },
                            supportingText = { Text(stringResource(R.string.settings_tts_url_help), fontSize = KeryxType.micro) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(12.dp))
                        var ttsKeyVisible by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = ttsApiKey,
                            onValueChange = onTtsApiKeyChanged,
                            label = { Text(stringResource(R.string.settings_api_key_optional)) },
                            visualTransformation = if (ttsKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { ttsKeyVisible = !ttsKeyVisible }) {
                                    Text(if (ttsKeyVisible) stringResource(R.string.settings_hide) else stringResource(R.string.settings_show), fontSize = KeryxType.caption)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = ttsVoice,
                            onValueChange = onTtsVoiceChanged,
                            label = { Text(stringResource(R.string.settings_tts_voice)) },
                            placeholder = { Text(stringResource(R.string.settings_example, "alloy / af_sky")) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = ttsModel,
                            onValueChange = onTtsModelChanged,
                            label = { Text(stringResource(R.string.settings_model_optional)) },
                            placeholder = { Text(stringResource(R.string.settings_model_hint, "tts-1")) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }

                    // --- Message Appearance ---
                    if (shown == "Appearance") SettingsCard(stringResource(R.string.settings_card_look)) {
                        val isDark by viewModel.prefs.isDarkTheme.collectAsState()
                        SettingsAnchor(SettingsRow.APPEARANCE_THEME) {
                            SettingsChoiceLabel(stringResource(R.string.settings_row_appearance_theme))
                            KeryxSegmented(
                                options = listOf("system" to stringResource(R.string.settings_theme_system), "light" to stringResource(R.string.settings_theme_light), "dark" to stringResource(R.string.settings_theme_dark)),
                                selected = when (isDark) { null -> "system"; true -> "dark"; false -> "light" },
                                onSelect = { viewModel.prefs.toggleTheme(when (it) { "dark" -> true; "light" -> false; else -> null }) },
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        SettingsAnchor(SettingsRow.APPEARANCE_BUBBLES) {
                            SettingsChoiceLabel(stringResource(R.string.settings_bubbles))
                            // Makeover: pick by looking — four little conversations, drawn with
                            // the real bubble look and your accents.
                            BubbleStylePicker(selected = bubbleStyle, onSelect = onBubbleStyleChanged)
                        }
                        Spacer(Modifier.height(18.dp))
                        SettingsAnchor(SettingsRow.APPEARANCE_TEXT) {
                            SettingsChoiceLabel(stringResource(R.string.settings_row_appearance_text))
                            val sizes = listOf("small" to 0.85f, "default" to 1.0f, "large" to 1.2f)
                            val sizeLabels = mapOf(
                                "small" to stringResource(R.string.settings_text_small),
                                "default" to stringResource(R.string.settings_text_default),
                                "large" to stringResource(R.string.settings_text_large),
                            )
                            KeryxSegmented(
                                options = sizes.map { it.first to sizeLabels.getValue(it.first) },
                                selected = sizes.firstOrNull { kotlin.math.abs(messageTextScale - it.second) < 0.01f }?.first ?: "default",
                                onSelect = { pick -> sizes.firstOrNull { it.first == pick }?.let { onMessageTextScaleChanged(it.second) } },
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        // Makeover: palettes first — pairs that already go together, one tap for
                        // both — then each accent's presets, and the full wheel only behind the
                        // rainbow circle. Two 180dp wheels used to open the page.
                        SettingsAnchor(SettingsRow.APPEARANCE_ACCENT) {
                            SettingsChoiceLabel(stringResource(R.string.settings_palette))
                            AccentPalettes(currentAccentColor, currentAccentColor2) { a, b ->
                                onAccentColorChanged(a); onAccentColor2Changed(b)
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        AccentRow(stringResource(R.string.settings_accent), currentAccentColor, onAccentColorChanged, discSize = 180.dp)
                        Spacer(Modifier.height(16.dp))
                        AccentRow(stringResource(R.string.settings_accent_2), currentAccentColor2, onAccentColor2Changed, discSize = 150.dp)
                        Text(
                            stringResource(R.string.settings_accent_2_help),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = KeryxType.micro,
                            modifier = Modifier.padding(top = 6.dp),
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
                            Text(stringResource(R.string.settings_accent_preview), color = currentAccentColor2, fontWeight = FontWeight.Bold, fontSize = KeryxType.bodyLarge)
                        }
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = onResetAppearance, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text(stringResource(R.string.settings_reset_default))
                        }
                    }

                    // --- Privacy & Security ---
                    if (shown == "Privacy & Security") SettingsCard(stringResource(R.string.settings_section_privacy), anchor = SettingsRow.PRIVACY_LOCK) {
                        SettingsSwitchRow(
                            anchor = SettingsRow.PRIVACY_LOCK,
                            title = stringResource(R.string.settings_row_privacy_lock),
                            subtitle = stringResource(R.string.settings_lock_sub),
                            checked = biometricLockEnabled,
                            onCheckedChange = onBiometricLockChanged
                        )
                        // E2EE is a Matrix room property; the direct door has no rooms to encrypt.
                        if (!viewModel.transportIsDirect) {
                            Spacer(Modifier.height(8.dp))
                            SettingsSwitchRow(
                                anchor = SettingsRow.PRIVACY_E2EE,
                                title = stringResource(R.string.settings_row_privacy_e2ee),
                                subtitle = stringResource(R.string.settings_e2ee_sub),
                                checked = e2eeEnabled,
                                onCheckedChange = onE2eeChanged
                            )
                        }
                    }

                    // Senses owns its own preferences file and needs nothing from this screen, so
                    // it sits next to Privacy rather than taking thirty parameters of plumbing.
                    if (shown == "Privacy & Security") SettingsAnchor(SettingsRow.PRIVACY_SENSES) { chat.keryx.app.senses.SensesSettingsCard() }

                    // --- Interface ---
                    if (shown == "Appearance") SettingsCard(stringResource(R.string.settings_card_feel)) {
                        SettingsSwitchRow(
                            anchor = SettingsRow.APPEARANCE_HAPTICS,
                            title = stringResource(R.string.settings_row_appearance_haptics),
                            subtitle = stringResource(R.string.settings_haptics_sub),
                            checked = hapticsEnabled,
                            onCheckedChange = onHapticsChanged
                        )
                        Spacer(Modifier.height(16.dp))
                        SettingsAnchor(SettingsRow.APPEARANCE_LOADING) {
                            SettingsChoiceLabel("Loading animation")
                            // Makeover: each one runs in its own card, so you pick the motion,
                            // not its name.
                            LoadingStylePicker(
                                options = listOf(
                                    "Caduceus" to "Caduceus",
                                    "Quill" to "Quill",
                                    "Constellation" to "Constellation",
                                    "Braille" to "Braille",
                                    "Ember Drift" to "Ember",
                                ),
                                selected = animationStyle,
                                onSelect = onAnimationStyleChanged,
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        SettingsAnchor(SettingsRow.APPEARANCE_TRANSITION) {
                            val transition by viewModel.prefs.roomTransition.collectAsState()
                            SettingsChoiceLabel(stringResource(R.string.settings_row_appearance_transition))
                            KeryxSegmented(
                                options = RoomTransition.entries.map { it.key to stringResource(it.labelRes) },
                                selected = transition.key,
                                onSelect = { viewModel.prefs.setRoomTransition(RoomTransition.of(it)) },
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.settings_transition_blurb, stringResource(transition.blurbRes)),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = KeryxType.caption,
                            )
                            Spacer(Modifier.height(12.dp))
                            val speed by viewModel.prefs.roomTransitionSpeed.collectAsState()
                            SettingsChoiceLabel(stringResource(R.string.settings_transition_speed))
                            KeryxSegmented(
                                options = RoomTransitionSpeed.entries.map { it.key to stringResource(it.labelRes) },
                                selected = speed.key,
                                onSelect = { viewModel.prefs.setRoomTransitionSpeed(RoomTransitionSpeed.of(it)) },
                            )
                            // Makeover: the crossing itself, playing at the speed above — the
                            // same draw the transcript uses, between two little rooms.
                            Spacer(Modifier.height(12.dp))
                            RoomTransitionPreview(style = transition, speed = speed)
                        }
                    }

                    // --- Sessions (direct door): what left the list, and the sweep ---
                    if (shown == "Sessions") SettingsCard(stringResource(R.string.settings_card_archived), anchor = SettingsRow.SESSIONS_ARCHIVED) {
                        val archived by viewModel.sessions.archivedRooms.collectAsState()
                        LaunchedEffect(Unit) { viewModel.sessions.loadArchivedSessions() }
                        if (archived.isEmpty()) Text(
                            stringResource(R.string.settings_archived_none),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = KeryxType.caption,
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
                                    Text(room.name, fontSize = KeryxType.bodyLarge, fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    if (room.preview.isNotBlank()) Text(room.preview, fontSize = KeryxType.micro,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                }
                                TextButton(onClick = { viewModel.sessions.unarchiveSession(room.id) }) {
                                    Text(stringResource(R.string.settings_restore), fontSize = KeryxType.caption)
                                }
                            }
                        }
                    }
                    if (shown == "Sessions") SettingsCard(stringResource(R.string.settings_card_pruning), anchor = SettingsRow.SESSIONS_PRUNE) {
                        var pruneOpen by remember { mutableStateOf(false) }
                        Text(
                            stringResource(R.string.settings_prune_blurb),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = KeryxType.caption,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { pruneOpen = true }, shape = RoundedCornerShape(KeryxRadius.field)) {
                            Text(stringResource(R.string.settings_prune_button), fontSize = KeryxType.body)
                        }
                        if (pruneOpen) SessionPruneDialog(viewModel = viewModel, onDismiss = { pruneOpen = false })
                    }

                    if (shown == "About") SettingsCard(stringResource(R.string.settings_card_tour), anchor = SettingsRow.ABOUT_TOUR) {
                        Text(
                            stringResource(R.string.settings_tour_blurb),
                            fontSize = KeryxType.body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.requestTour(chat.keryx.core.tour.TourPreset.QUICK) },
                                shape = RoundedCornerShape(KeryxRadius.field),
                            ) { Text(stringResource(R.string.settings_tour_quick), fontSize = KeryxType.body) }
                            OutlinedButton(
                                onClick = { viewModel.requestTour(chat.keryx.core.tour.TourPreset.FULL) },
                                shape = RoundedCornerShape(KeryxRadius.field),
                            ) { Text(stringResource(R.string.settings_tour_full), fontSize = KeryxType.body) }
                        }
                        TextButton(onClick = { viewModel.requestWhatsNew() }) {
                            Text(stringResource(R.string.settings_whats_new, "3.0"), fontSize = KeryxType.body)
                        }
                        Spacer(Modifier.height(4.dp))
                        // First-visit hints (3.0): Missions, Runs and the rest explain themselves once.
                        val hintsAgain = stringResource(R.string.settings_hints_again_toast)
                        TextButton(onClick = {
                            viewModel.prefs.showHintsAgain()
                            viewModel.toast(hintsAgain)
                        }) { Text(stringResource(R.string.settings_hints_again), fontSize = KeryxType.body) }
                    }
                    if (shown == "About") SettingsCard(stringResource(R.string.settings_card_diagnostics), anchor = SettingsRow.ABOUT_CRASH) {
                        val diagContext = androidx.compose.ui.platform.LocalContext.current
                        var crashText by remember { mutableStateOf(chat.keryx.app.CrashLog.read(diagContext)) }
                        Text(
                            text = if (crashText.isBlank()) stringResource(R.string.settings_crash_none)
                                else stringResource(R.string.settings_crash_size, crashText.length / 1024),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = KeryxType.body,
                        )
                        Text(
                            text = stringResource(R.string.settings_crash_note),
                            color = chat.keryx.app.theme.quietInk(alpha = 0.6f),
                            fontSize = KeryxType.micro,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { chat.keryx.app.CrashLog.share(diagContext) },
                                enabled = crashText.isNotBlank(),
                                shape = RoundedCornerShape(KeryxRadius.field),
                            ) { Text(stringResource(R.string.settings_share), fontSize = KeryxType.body) }
                            OutlinedButton(
                                onClick = {
                                    chat.keryx.app.CrashLog.clear(diagContext)
                                    crashText = ""
                                },
                                enabled = crashText.isNotBlank(),
                                shape = RoundedCornerShape(KeryxRadius.field),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            ) { Text(stringResource(R.string.settings_clear), fontSize = KeryxType.body) }
                        }
                    }
                    if (shown == "About") SettingsCard("Keryx", anchor = SettingsRow.ABOUT_VERSION) {
                        val aboutContext = androidx.compose.ui.platform.LocalContext.current
                        val lines = listOfNotNull(
                            stringResource(R.string.settings_about_app) to "Keryx v${chat.keryx.app.BuildConfig.VERSION_NAME} (${chat.keryx.app.BuildConfig.VERSION_CODE})",
                            stringResource(R.string.settings_about_door) to (if (viewModel.transportIsDirect) stringResource(R.string.settings_door_direct) else "Matrix"),
                            hubHealth.data?.version?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.settings_section_gateway) to "hermes-agent $it" },
                            caps?.model?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.settings_card_brain) to it },
                            "Android" to "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
                        )
                        lines.forEach { (k, v) ->
                            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                                Text(k, fontSize = KeryxType.body, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(86.dp))
                                Text(v, fontSize = KeryxType.body, fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        // The Desktop's "Copy error details": versions, door, brain — the lines a
                        // bug report needs and a person never remembers. No secrets ride along.
                        val copied = stringResource(R.string.settings_diagnostics_copied)
                        OutlinedButton(
                            onClick = {
                                val text = lines.joinToString("\n") { (k, v) -> "$k: $v" }
                                aboutContext.getSystemService(android.content.ClipboardManager::class.java)
                                    ?.setPrimaryClip(android.content.ClipData.newPlainText("Keryx diagnostics", text))
                                viewModel.toast(copied)
                            },
                            shape = RoundedCornerShape(KeryxRadius.field),
                        ) { Text(stringResource(R.string.settings_copy_diagnostics), fontSize = KeryxType.body) }
                    }

                    Spacer(Modifier.height(40.dp))
                }
                }
                }
            }
          }
        }
}

/**
 * The hub ⇄ page move (makeover): going deeper, the page arrives from the right a short way
 * while the hub drifts left and fades; coming home is the mirror. Short distances on the app's
 * springs — the Hermes Mobile feel is speed with a soft landing, not a long swipe.
 */
private fun settingsPageTransition(deeper: Boolean, reduced: Boolean): androidx.compose.animation.ContentTransform {
    if (reduced) return androidx.compose.animation.ContentTransform(
        androidx.compose.animation.fadeIn(KeryxMotion.leave),
        androidx.compose.animation.fadeOut(KeryxMotion.leave),
    )
    val sign = if (deeper) 1 else -1
    val enter = androidx.compose.animation.slideInHorizontally(KeryxMotion.settleInt) { w -> sign * w / 5 } +
        androidx.compose.animation.fadeIn(KeryxMotion.settle)
    val exit = androidx.compose.animation.slideOutHorizontally(KeryxMotion.leaveInt) { w -> -sign * w / 8 } +
        androidx.compose.animation.fadeOut(KeryxMotion.leave)
    return androidx.compose.animation.ContentTransform(enter, exit)
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
    val currentAccent by viewModel.prefs.accentColor.collectAsState()
    val currentAccent2 by viewModel.prefs.accentColor2.collectAsState()
    val matrixUrl by viewModel.prefs.matrixUrl.collectAsState()
    val agentMatrixId by viewModel.prefs.agentMatrixId.collectAsState()
    val matrixToken by viewModel.prefs.matrixToken.collectAsState()
    val biometricLockEnabled by viewModel.prefs.biometricLock.collectAsState()
    val e2eeEnabled by viewModel.prefs.e2eeEnabled.collectAsState()
    val hapticsEnabled by viewModel.prefs.hapticsEnabled.collectAsState()
    val animationStyle by viewModel.prefs.animationStyle.collectAsState()
    val bubbleStyle by viewModel.prefs.bubbleStyle.collectAsState()
    val messageTextScale by viewModel.prefs.messageTextScale.collectAsState()
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
    val showTelemetry by viewModel.prefs.showTelemetry.collectAsState()
    val missionAlertsEnabled by viewModel.missions.alertsEnabled.collectAsState()
    val pushEnabled by viewModel.pushEnabled.collectAsState()
    val pushGatewayUrl by viewModel.pushGatewayUrl.collectAsState()

    SettingsScreen(
        viewModel = viewModel,
        currentAccentColor = currentAccent,
        onAccentColorChanged = { viewModel.prefs.setAccentColor(it) },
        currentAccentColor2 = currentAccent2,
        onAccentColor2Changed = { viewModel.prefs.setAccentColor2(it) },
        currentUserId = currentUserId,
        matrixUrl = matrixUrl,
        onMatrixUrlChanged = { viewModel.prefs.setMatrixUrl(it) },
        agentMatrixId = agentMatrixId,
        onAgentMatrixIdChanged = { viewModel.prefs.setAgentMatrixId(it) },
        matrixToken = matrixToken,
        onMatrixTokenChanged = { viewModel.prefs.setMatrixToken(it) },
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
        onShowTelemetryChanged = { viewModel.prefs.setShowTelemetry(it) },
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
                        viewModel.toast(context.getString(R.string.settings_push_need_url))
                    }
                    chat.keryx.app.notify.PushManager.EnableResult.BuiltinActive ->
                        viewModel.toast(context.getString(R.string.settings_push_builtin_active))
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
        onBiometricLockChanged = { viewModel.prefs.setBiometricLock(it) },
        e2eeEnabled = e2eeEnabled,
        onE2eeChanged = { viewModel.prefs.setE2eeEnabled(it) },
        hapticsEnabled = hapticsEnabled,
        onHapticsChanged = { viewModel.prefs.setHapticsEnabled(it) },
        animationStyle = animationStyle,
        onAnimationStyleChanged = { viewModel.prefs.setAnimationStyle(it) },
        bubbleStyle = bubbleStyle,
        onBubbleStyleChanged = { viewModel.prefs.setBubbleStyle(it) },
        messageTextScale = messageTextScale,
        onMessageTextScaleChanged = { viewModel.prefs.setMessageTextScale(it) },
        onResetAppearance = { viewModel.prefs.resetMessageAppearance() },
        onLoginRequested = { user, pass, callback ->
            viewModel.doors.loginToMatrix(user, pass, callback)
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
