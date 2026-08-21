package chat.keryx.app.presentation.ui

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
import chat.keryx.app.presentation.ui.components.BrailleSnakeAnimation

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
    val initialUrl by viewModel.matrixUrl.collectAsState()
    val initialAgent by viewModel.agentMatrixId.collectAsState()
    val initialInsecure by viewModel.allowInsecure.collectAsState()

    var homeserver by remember { mutableStateOf(initialUrl) }
    var username by remember { mutableStateOf(viewModel.lastMatrixUsername) }
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

    val accent = MaterialTheme.colorScheme.primary
    val focusManager = LocalFocusManager.current
    val context = androidx.compose.ui.platform.LocalContext.current

    // The door change is a different process spine — relaunch cleanly into it.
    fun relaunch() = chat.keryx.app.presentation.ui.components.relaunchApp(context)

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
                .size(180.dp),
            contentAlignment = Alignment.Center,
        ) {
            BrailleSnakeAnimation(
                modifier = Modifier.fillMaxSize(),
                color = accent,
                running = true,
                progress = true,
            )
        }

        chat.keryx.app.presentation.ui.components.KeryxWordmark(
            modifier = Modifier.padding(top = 8.dp),
            fontSize = 34.sp,
        )
        Text(
            text = when {
                connecting -> "Connecting…"
                directDoor -> "Connect straight to your gateway"
                else -> "Connect to your homeserver"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

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
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Door("Matrix", !directDoor) { directDoor = false; error = null }
            Door("Direct to gateway", directDoor) { directDoor = true; error = null }
        }

        if (directDoor) {
            OutlinedTextField(
                value = directUrl,
                onValueChange = { directUrl = it; error = null },
                label = { Text("Gateway URL") },
                placeholder = { Text("http://your-host:8686") },
                singleLine = true,
                enabled = !connecting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = directKey,
                onValueChange = { directKey = it; error = null },
                label = { Text("API key") },
                singleLine = true,
                enabled = !connecting,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (passwordVisible) "Hide key" else "Show key",
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
                        "Allow self-signed certificates",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                    )
                    Text(
                        "Only for local / self-hosted gateways",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                Switch(checked = allowInsecure, onCheckedChange = { allowInsecure = it }, enabled = !connecting)
            }
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
            Button(
                onClick = {
                    error = null
                    connecting = true
                    viewModel.setAllowInsecure(allowInsecure)
                    viewModel.loginToGateway(directUrl.trim(), directKey.trim()) { ok, needsRestart, message ->
                        if (!ok) {
                            connecting = false
                            error = message ?: "Login failed"
                        } else if (needsRestart) {
                            relaunch()
                        } else {
                            connecting = false
                        }
                    }
                },
                enabled = !connecting && directUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 24.dp),
            ) {
                if (connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Connect", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            return@Column
        }

        if (viewModel.transportIsDirect) {
            // Booted on the direct spine: a Matrix login needs the Matrix transport, which is
            // built at startup. Cross the doorway first; credentials come after the relaunch.
            Text(
                "Keryx is currently in direct-gateway mode. Switching to Matrix restarts the app.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(
                onClick = { viewModel.switchTransport("matrix"); relaunch() },
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 24.dp),
            ) {
                Text("Switch to Matrix", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            return@Column
        }

        OutlinedTextField(
            value = homeserver,
            onValueChange = { homeserver = it; error = null },
            label = { Text("Homeserver URL") },
            placeholder = { Text("https://matrix.org") },
            singleLine = true,
            enabled = !connecting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it; error = null },
            label = { Text("Username") },
            placeholder = { Text("you") },
            singleLine = true,
            enabled = !connecting,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text("Password") },
            singleLine = true,
            enabled = !connecting,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )

        TextButton(
            onClick = { showAdvanced = !showAdvanced },
            modifier = Modifier.align(Alignment.Start),
        ) {
            Text(if (showAdvanced) "Hide advanced" else "Advanced")
        }

        if (showAdvanced) {
            OutlinedTextField(
                value = agentId,
                onValueChange = { agentId = it },
                label = { Text("Hermes agent Matrix ID (optional)") },
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
                        "Allow self-signed certificates",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                    )
                    Text(
                        "Only for local / self-hosted servers",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                Switch(checked = allowInsecure, onCheckedChange = { allowInsecure = it }, enabled = !connecting)
            }
        }

        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
        }

        Button(
            onClick = {
                error = null
                connecting = true
                viewModel.setMatrixUrl(homeserver.trim())
                viewModel.setAgentMatrixId(agentId.trim())
                viewModel.setAllowInsecure(allowInsecure)
                viewModel.loginToMatrix(username.trim(), password) { success, message ->
                    connecting = false
                    if (!success) error = message ?: "Login failed"
                }
            },
            enabled = !connecting && homeserver.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 24.dp),
        ) {
            if (connecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("Connect", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
