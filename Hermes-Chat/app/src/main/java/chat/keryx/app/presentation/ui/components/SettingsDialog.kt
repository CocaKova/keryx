package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlin.math.atan2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentAccentColor: Color,
    onAccentColorChanged: (Color) -> Unit,
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
    onTestLink: () -> Unit,
    showTelemetry: Boolean,
    onShowTelemetryChanged: (Boolean) -> Unit,
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
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("Settings", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onDismissRequest) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                ) {
                    // --- Account ---
                    SettingsCard("Account") {
                        Text(
                            text = currentUserId ?: "Not signed in",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        )
                        Text(
                            text = matrixUrl.ifBlank { "No homeserver set" },
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

                    // --- Connection ---
                    SettingsCard("Connection") {
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
                            label = { Text("Hermes Agent Matrix ID") },
                            placeholder = { Text("@hermes:example.com") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(8.dp))
                        SettingsSwitchRow(
                            title = "Allow self-signed certificates",
                            subtitle = "Only for local / self-hosted servers",
                            checked = allowInsecure,
                            onCheckedChange = onAllowInsecureChanged,
                        )

                        var reauth by remember { mutableStateOf(false) }
                        TextButton(onClick = { reauth = !reauth }) {
                            Text(if (reauth) "Hide re-authenticate" else "Re-authenticate")
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
                    SettingsCard("Hermes Link") {
                        SettingsSwitchRow(
                            title = "Live token streaming",
                            subtitle = "Stream replies over the gateway side-channel (SSE); falls back to Matrix sync when unreachable",
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
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Test link", fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        SettingsSwitchRow(
                            title = "Show telemetry",
                            subtitle = "Automated check-ins and the runtime footer as quiet blocks",
                            checked = showTelemetry,
                            onCheckedChange = onShowTelemetryChanged,
                        )
                    }

                    // --- Message Appearance ---
                    SettingsCard("Message Appearance") {
                        Text("Bubble Style", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Solid", "Gradient", "Glass").forEach { style ->
                                FilterChip(
                                    selected = bubbleStyle == style,
                                    onClick = { onBubbleStyleChanged(style) },
                                    label = { Text(style) }
                                )
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        Text("Text Size", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Small" to 0.85f, "Default" to 1.0f, "Large" to 1.2f).forEach { (label, scale) ->
                                FilterChip(
                                    selected = kotlin.math.abs(messageTextScale - scale) < 0.01f,
                                    onClick = { onMessageTextScaleChanged(scale) },
                                    label = { Text(label) }
                                )
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        Text("Accent Color", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.height(12.dp))
                        ColorWheel(
                            modifier = Modifier
                                .size(180.dp)
                                .clip(RoundedCornerShape(100.dp))
                                .align(Alignment.CenterHorizontally),
                            onColorSelected = onAccentColorChanged
                        )
                        Spacer(Modifier.height(12.dp))
                        val hexString = String.format("#%06X", (0xFFFFFF and currentAccentColor.toArgb()))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(currentAccentColor.copy(alpha = 0.2f))
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .align(Alignment.CenterHorizontally)
                        ) {
                            Text(hexString, color = currentAccentColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = onResetAppearance, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text("Reset to Default")
                        }
                    }

                    // --- Privacy & Security ---
                    SettingsCard("Privacy & Security") {
                        SettingsSwitchRow(
                            title = "Biometric App Lock",
                            subtitle = "Require FaceID/Fingerprint to open Keryx",
                            checked = biometricLockEnabled,
                            onCheckedChange = onBiometricLockChanged
                        )
                        Spacer(Modifier.height(8.dp))
                        SettingsSwitchRow(
                            title = "End-to-End Encryption",
                            subtitle = "Enable Matrix E2EE session management",
                            checked = e2eeEnabled,
                            onCheckedChange = onE2eeChanged
                        )
                    }

                    // --- Interface ---
                    SettingsCard("Interface") {
                        SettingsSwitchRow(
                            title = "Haptic Feedback",
                            subtitle = "Vibrate on interactions",
                            checked = hapticsEnabled,
                            onCheckedChange = onHapticsChanged
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("Loading Animation", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "Braille" to "⠋ Braille",
                                "Dots" to "○ Dots",
                                "ASCII Wave" to "▅ Wave"
                            ).forEach { (style, labelText) ->
                                FilterChip(
                                    selected = animationStyle == style,
                                    onClick = { onAnimationStyleChanged(style) },
                                    label = { Text(labelText) }
                                )
                            }
                        }
                    }

                    SettingsCard("Diagnostics") {
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
                                shape = RoundedCornerShape(12.dp),
                            ) { Text("Share", fontSize = 13.sp) }
                            OutlinedButton(
                                onClick = {
                                    chat.keryx.app.CrashLog.clear(diagContext)
                                    crashText = ""
                                },
                                enabled = crashText.isNotBlank(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            ) { Text("Clear", fontSize = 13.sp) }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Keryx v${chat.keryx.app.BuildConfig.VERSION_NAME}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                        )
                    }

                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}

/** A titled, rounded settings group card. */
@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        text = title.uppercase(),
        color = MaterialTheme.colorScheme.primary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp)
    )
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = MaterialTheme.colorScheme.primary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 16.dp)
    )
}

@Composable
fun SettingsSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(text = subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun ColorWheel(
    modifier: Modifier = Modifier,
    onColorSelected: (Color) -> Unit
) {
    var center by remember { mutableStateOf(Offset.Zero) }
    val colors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val angle = atan2(offset.y - center.y, offset.x - center.x)
                    val degrees = Math.toDegrees(angle.toDouble()).toFloat().let {
                        if (it < 0) it + 360f else it
                    }
                    onColorSelected(Color.hsv(degrees, 1f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val offset = change.position
                    val angle = atan2(offset.y - center.y, offset.x - center.x)
                    val degrees = Math.toDegrees(angle.toDouble()).toFloat().let {
                        if (it < 0) it + 360f else it
                    }
                    onColorSelected(Color.hsv(degrees, 1f, 1f))
                }
            }
    ) {
        center = Offset(size.width / 2, size.height / 2)
        drawCircle(brush = Brush.sweepGradient(colors, center), radius = size.minDimension / 2, center = center)
    }
}
