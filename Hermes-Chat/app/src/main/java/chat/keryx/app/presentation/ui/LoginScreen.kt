package chat.keryx.app.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.presentation.ChatViewModel
import androidx.compose.ui.res.stringResource
import chat.keryx.app.R
import chat.keryx.app.presentation.ui.components.BrailleSnakeAnimation
import chat.keryx.app.presentation.ui.components.KeryxType

/**
 * First-run connect/login experience. The Braille snake traces the contour of an emblem while the
 * user picks a door and enters credentials.
 *
 * Two doors (absorption plan §5, Phase 4): a Matrix account — the herald's home — or a
 * hermes-agent gateway URL and API key, no homeserver anywhere. The door decides the process
 * spine, so crossing to the other one relaunches the app (the transport is built at startup).
 */
@Composable
fun LoginScreen(viewModel: ChatViewModel) {
    val initialUrl by viewModel.prefs.matrixUrl.collectAsState()
    val initialAgent by viewModel.prefs.agentMatrixId.collectAsState()
    val initialInsecure by viewModel.allowInsecure.collectAsState()

    var homeserver by remember { mutableStateOf(initialUrl) }
    var username by remember { mutableStateOf(viewModel.doors.lastMatrixUsername) }
    var password by remember { mutableStateOf("") }
    var agentId by remember { mutableStateOf(initialAgent) }
    var allowInsecure by remember { mutableStateOf(initialInsecure) }
    var showAdvanced by remember { mutableStateOf(initialInsecure || initialAgent.isNotEmpty()) }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }

    // The chosen door. Starts on whichever spine this process booted with.
    var directDoor by remember { mutableStateOf(viewModel.transportIsDirect) }
    var directUrl by remember { mutableStateOf(viewModel.directGatewayUrl) }
    var directKey by remember { mutableStateOf(viewModel.directApiKey) }
    // The fleet (2.11): the device name the gateway wears everywhere. Prefilled from the row
    // this phone last stood on; blank = derived from the host when the sign-in lands.
    var directName by remember { mutableStateOf(viewModel.doors.fleet.value.active?.name.orEmpty()) }
    // Pairing by QR (3.0): what was scanned, held until Connect. While it's set the address is the
    // code's, not editable (the code only pairs that gateway's Link).
    var pair by remember { mutableStateOf<chat.keryx.core.pair.PairingLink?>(null) }
    var scanning by remember { mutableStateOf(false) }

    val accent = MaterialTheme.colorScheme.primary
    val focusManager = LocalFocusManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val loginFailed = stringResource(R.string.settings_login_failed)

    // The door change is a different process spine — relaunch cleanly into it.
    fun relaunch() = chat.keryx.app.presentation.ui.components.relaunchApp(context)

    // The first run (3.0): a phone that has never had a door opens on the welcome, not a form.
    var welcome by remember { mutableStateOf(viewModel.doors.firstRun) }

    // The herald arrives (3.0): the welcome comes in once, in stages: the mark, the name, then
    // what it's for. Read in the draw phase only, so it costs no recomposition. Reduced motion
    // (battery saver included) and every screen but the welcome start it finished.
    val reduced by chat.keryx.app.presentation.ui.components.rememberReducedMotion()
    val arrival = remember { androidx.compose.animation.core.Animatable(if (welcome) 0f else 1f) }
    androidx.compose.runtime.LaunchedEffect(reduced) {
        if (reduced) arrival.snapTo(1f)
        else arrival.animateTo(1f, androidx.compose.animation.core.tween(HERALD_ARRIVAL_MS, easing = androidx.compose.animation.core.LinearEasing))
    }
    fun Modifier.heraldStage(from: Float, to: Float, riseDp: Float = 0f, grow: Boolean = false) =
        this.then(Modifier.graphicsLayer {
            val e = chat.keryx.app.presentation.ui.components.KeryxMotion.arcane
                .transform(((arrival.value - from) / (to - from)).coerceIn(0f, 1f))
            alpha = e
            translationY = (1f - e) * riseDp * density
            if (grow) { val k = 0.86f + 0.14f * e; scaleX = k; scaleY = k }
        })

    if (scanning) {
        chat.keryx.app.presentation.pair.PairScanDialog(
            onDismiss = { scanning = false },
            onScanned = { p ->
                scanning = false
                pair = p
                directUrl = p.gateway
                if (directName.isBlank()) directName = p.name
                error = null
            },
        )
    }

    // The demo is its own process spine too; it arrives with the tour already running.
    fun lookAround() {
        viewModel.doors.answerWelcome()
        viewModel.doors.enterDemo()
        chat.keryx.app.presentation.ui.components.relaunchApp(context) {
            putExtra(chat.keryx.app.presentation.tour.KeryxTour.EXTRA_TOUR, chat.keryx.core.tour.TourPreset.FULL.wire)
        }
    }

    @Composable
    fun LookAroundLink() {
        TextButton(onClick = ::lookAround, enabled = !connecting, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.settings_login_look_around_link), fontSize = KeryxType.body)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 48.dp)
                .size(180.dp)
                .heraldStage(0f, 0.45f, grow = true),
            contentAlignment = Alignment.Center,
        ) {
            BrailleSnakeAnimation(
                modifier = Modifier.fillMaxSize(),
                color = accent,
                // Moving under Battery Saver too (the spinner rule): it is the "Connecting…" sign.
                running = true,
                progress = true,
            )
        }

        chat.keryx.app.presentation.ui.components.KeryxWordmark(
            modifier = Modifier.padding(top = 8.dp).heraldStage(0.25f, 0.7f, riseDp = 14f),
            fontSize = KeryxType.splash,
        )
        Text(
            text = when {
                welcome -> stringResource(R.string.settings_login_tagline)
                connecting -> stringResource(R.string.settings_login_connecting)
                directDoor -> stringResource(R.string.settings_login_direct_title)
                else -> stringResource(R.string.settings_login_matrix_title)
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = KeryxType.bodyLarge,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp).heraldStage(0.45f, 0.8f, riseDp = 8f),
        )

        if (welcome) {
            // What Keryx is for, in three lines: an agent at work, not a chat window.
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp).heraldStage(0.6f, 0.9f, riseDp = 8f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (line in listOf(
                    stringResource(R.string.settings_login_pitch_watch),
                    stringResource(R.string.settings_login_pitch_notify),
                    stringResource(R.string.settings_login_pitch_missions),
                )) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier
                                .padding(top = 9.dp, end = 12.dp)
                                .size(6.dp)
                                .background(accent, CircleShape)
                        )
                        Text(line, color = MaterialTheme.colorScheme.onSurface, fontSize = KeryxType.bodyLarge)
                    }
                }
            }
            Button(
                onClick = {
                    viewModel.doors.answerWelcome()
                    welcome = false
                    directDoor = true
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(52.dp).heraldStage(0.7f, 1f),
            ) {
                Text(stringResource(R.string.settings_demo_connect), fontSize = KeryxType.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            androidx.compose.material3.OutlinedButton(
                onClick = ::lookAround,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp).heraldStage(0.7f, 1f),
            ) {
                Text(stringResource(R.string.settings_login_look_around), fontSize = KeryxType.titleLarge)
            }
            Text(
                stringResource(R.string.settings_login_demo_blurb),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = KeryxType.caption,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 32.dp).heraldStage(0.7f, 1f),
            )
            return@Column
        }

        // The two doors. A quiet segmented pair — the door is a decision, not a setting.
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            @Composable
            fun Door(label: String, selected: Boolean, onClick: () -> Unit) {
                TextButton(onClick = onClick, enabled = !connecting) {
                    Text(
                        label,
                        fontSize = KeryxType.bodyLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Door("Matrix", !directDoor) { directDoor = false; error = null }
            Door(stringResource(R.string.settings_door_direct), directDoor) { directDoor = true; error = null }
        }

        if (directDoor) {
            val scanned = pair
            if (scanned == null) {
                chat.keryx.app.presentation.pair.ScanPairingButton(
                    enabled = !connecting,
                    onClick = { scanning = true },
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            } else {
                chat.keryx.app.presentation.pair.PairingBanner(
                    scanned,
                    onCancel = { pair = null; directUrl = ""; error = null },
                    enabled = !connecting,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
            OutlinedTextField(
                value = directName,
                onValueChange = { directName = it; error = null },
                label = { Text(stringResource(R.string.settings_login_name)) },
                placeholder = { Text(stringResource(R.string.settings_login_name_hint)) },
                singleLine = true,
                enabled = !connecting,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
            OutlinedTextField(
                value = directUrl,
                onValueChange = { directUrl = it; error = null },
                label = { Text(stringResource(R.string.settings_row_link_url)) },
                placeholder = { Text("http://your-host:8686") },
                singleLine = true,
                enabled = !connecting && pair == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = directKey,
                onValueChange = { directKey = it; error = null },
                label = { Text(stringResource(R.string.settings_login_api_key)) },
                singleLine = true,
                enabled = !connecting,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (passwordVisible) stringResource(R.string.settings_login_hide_key) else stringResource(R.string.settings_login_show_key),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_row_connection_insecure),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = KeryxType.title,
                    )
                    Text(
                        stringResource(R.string.settings_insecure_gateways),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = KeryxType.caption,
                    )
                }
                Switch(checked = allowInsecure, onCheckedChange = { allowInsecure = it }, enabled = !connecting)
            }
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = KeryxType.body,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
            Button(
                onClick = {
                    error = null
                    connecting = true
                    viewModel.setAllowInsecure(allowInsecure)
                    viewModel.doors.loginToGateway(
                        directUrl.trim(),
                        directKey.trim(),
                        launchBrowser = { url ->
                            // A gated gateway signs in through the SYSTEM browser (RFC 8252) —
                            // the password manager lives there, not in any embedded webview.
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url),
                                ),
                            )
                        },
                        onResult = { ok, needsRestart, message ->
                            if (!ok) {
                                connecting = false
                                error = message ?: loginFailed
                            } else if (needsRestart) {
                                relaunch()
                            } else {
                                connecting = false
                            }
                        },
                        name = directName.trim(),
                        pair = pair,
                        deviceName = chat.keryx.app.presentation.pair.phoneName(context),
                    )
                },
                enabled = !connecting && directUrl.isNotBlank(),
                // Padding before the height: inside it, the 24 dp came out of the button.
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(52.dp),
            ) {
                if (connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.settings_login_connect), fontSize = KeryxType.titleLarge, fontWeight = FontWeight.SemiBold)
                }
            }
            LookAroundLink()
            return@Column
        }

        if (viewModel.transportIsDirect) {
            // Booted on the direct spine: a Matrix login needs the Matrix transport, which is
            // built at startup. Cross the doorway first; credentials come after the relaunch.
            Text(
                stringResource(R.string.settings_login_direct_mode_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = KeryxType.body,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(
                onClick = { viewModel.doors.switchTransport("matrix"); relaunch() },
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(52.dp),
            ) {
                Text(stringResource(R.string.settings_switch_to_matrix), fontSize = KeryxType.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            return@Column
        }

        OutlinedTextField(
            value = homeserver,
            onValueChange = { homeserver = it; error = null },
            label = { Text(stringResource(R.string.settings_row_connection_homeserver)) },
            placeholder = { Text("https://matrix.org") },
            singleLine = true,
            enabled = !connecting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it; error = null },
            label = { Text(stringResource(R.string.settings_username)) },
            placeholder = { Text(stringResource(R.string.settings_login_username_hint)) },
            singleLine = true,
            enabled = !connecting,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text(stringResource(R.string.settings_password)) },
            singleLine = true,
            enabled = !connecting,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (passwordVisible) stringResource(R.string.settings_login_hide_password) else stringResource(R.string.settings_login_show_password),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )

        TextButton(
            onClick = { showAdvanced = !showAdvanced },
            modifier = Modifier.align(Alignment.Start),
        ) {
            Text(if (showAdvanced) stringResource(R.string.settings_login_hide_advanced) else stringResource(R.string.settings_login_advanced))
        }

        if (showAdvanced) {
            OutlinedTextField(
                value = agentId,
                onValueChange = { agentId = it },
                label = { Text(stringResource(R.string.settings_login_agent_id)) },
                placeholder = { Text("@hermes:your.server") },
                singleLine = true,
                enabled = !connecting,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_row_connection_insecure),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = KeryxType.title,
                    )
                    Text(
                        stringResource(R.string.settings_insecure_servers),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = KeryxType.caption,
                    )
                }
                Switch(checked = allowInsecure, onCheckedChange = { allowInsecure = it }, enabled = !connecting)
            }
        }

        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                fontSize = KeryxType.body,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
        }

        Button(
            onClick = {
                error = null
                connecting = true
                viewModel.prefs.setMatrixUrl(homeserver.trim())
                viewModel.prefs.setAgentMatrixId(agentId.trim())
                viewModel.setAllowInsecure(allowInsecure)
                viewModel.doors.loginToMatrix(username.trim(), password) { success, message ->
                    connecting = false
                    if (!success) error = message ?: loginFailed
                }
            },
            enabled = !connecting && homeserver.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(52.dp),
        ) {
            if (connecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.settings_login_connect), fontSize = KeryxType.titleLarge, fontWeight = FontWeight.SemiBold)
            }
        }
        LookAroundLink()
    }
}

/** The welcome's first arrival, end to end: long enough to read as an entrance, short enough
 *  that nobody waits on it (the buttons are live from the first frame). */
private const val HERALD_ARRIVAL_MS = 1_100
