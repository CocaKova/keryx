package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlin.math.atan2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
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
    onTestLink: () -> Unit,
    showTelemetry: Boolean,
    onShowTelemetryChanged: (Boolean) -> Unit,
    missionAlertsEnabled: Boolean,
    onMissionAlertsChanged: (Boolean) -> Unit,
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
            // Hub-and-spoke: null = the section list; a name = that section's page.
            // One long scroll of seven dense cards was the old layout — cluttered.
            var section by remember { mutableStateOf<String?>(null) }
            BackHandler(enabled = section != null) { section = null }

            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(section ?: "Settings", fontWeight = FontWeight.Bold) },
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
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                ) {
                    if (section == null) {
                        Spacer(Modifier.height(4.dp))
                        SettingsHubRow(Icons.Default.Person, "Account",
                            currentUserId ?: "Not signed in") { section = "Account" }
                        SettingsHubRow(Icons.Default.Dns, "Connection",
                            matrixUrl.ifBlank { "Homeserver & agent" }) { section = "Connection" }
                        SettingsHubRow(Icons.Default.Bolt, "Hermes Link",
                            if (sideChannelEnabled) "Live token streaming on" else "Live token streaming off") { section = "Hermes Link" }
                        SettingsHubRow(Icons.Default.Palette, "Appearance",
                            "Bubbles, text size, accent colors") { section = "Appearance" }
                        SettingsHubRow(Icons.Default.Lock, "Privacy & Security",
                            listOfNotNull(
                                if (biometricLockEnabled) "App lock" else null,
                                if (e2eeEnabled) "E2EE" else null,
                            ).ifEmpty { listOf("App lock & encryption") }.joinToString(" · ")) { section = "Privacy & Security" }
                        SettingsHubRow(Icons.Default.Tune, "Interface",
                            "Haptics & loading animation") { section = "Interface" }
                        SettingsHubRow(Icons.Default.BugReport, "Diagnostics",
                            "Crash log · Keryx v${chat.keryx.app.BuildConfig.VERSION_NAME}") { section = "Diagnostics" }
                    }

                    // --- Account ---
                    if (section == "Account") SettingsCard("Account") {
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
                    if (section == "Connection") SettingsCard("Connection") {
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
                    if (section == "Hermes Link") SettingsCard("Hermes Link") {
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
                        SettingsSwitchRow(
                            title = "Mission alerts",
                            subtitle = "Notify when a mission completes, blocks, or gives up — checked in the background every 15 minutes",
                            checked = missionAlertsEnabled,
                            onCheckedChange = onMissionAlertsChanged,
                        )
                    }

                    // --- Message Appearance ---
                    if (section == "Appearance") SettingsCard("Message Appearance") {
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
                                .clip(RoundedCornerShape(8.dp))
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
                    if (section == "Privacy & Security") SettingsCard("Privacy & Security") {
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
                    if (section == "Interface") SettingsCard("Interface") {
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

                    if (section == "Diagnostics") SettingsCard("Diagnostics") {
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

/** One row of the settings hub: icon, title, live subtitle, chevron. */
@Composable
private fun SettingsHubRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Icon(
            icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                subtitle, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Default.ChevronRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
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

/** A quick-tap starting set; the disc/slider/hex refine from there. */
private val SWATCHES = listOf(
    0xFFE0A458, 0xFF8B5CF6, 0xFFE53935, 0xFFEC4899,
    0xFF3B82F6, 0xFF10B981, 0xFFF59E0B, 0xFF94A3B8,
).map { Color(it) }

/**
 * Full HSV picker: hue/saturation disc (angle = hue, radius = saturation) + brightness slider +
 * editable hex field + preset swatches. The old wheel only ever emitted `hsv(hue, 1, 1)` — pure
 * neon hues — which is why precise colors were unreachable.
 */
@Composable
fun ColorPickerPanel(
    current: Color,
    onColorSelected: (Color) -> Unit,
    modifier: Modifier = Modifier,
    discSize: androidx.compose.ui.unit.Dp = 180.dp,
) {
    var hue by remember { mutableStateOf(0f) }
    var sat by remember { mutableStateOf(1f) }
    var bright by remember { mutableStateOf(1f) }
    var lastEmitted by remember { mutableStateOf<Int?>(null) }
    var hexText by remember { mutableStateOf("") }
    var hexFocused by remember { mutableStateOf(false) }

    fun deriveFrom(color: Color) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        hue = hsv[0]; sat = hsv[1]; bright = hsv[2]
    }
    // External change (reset button, dialog reopen) re-derives; our own echo must not, or a
    // gray/desaturated pick would snap the hue slider back to 0.
    LaunchedEffect(current) {
        if (current.toArgb() != lastEmitted) deriveFrom(current)
        if (!hexFocused) hexText = String.format("%06X", 0xFFFFFF and current.toArgb())
    }
    fun apply(color: Color, rederive: Boolean) {
        if (rederive) deriveFrom(color)
        lastEmitted = color.toArgb()
        onColorSelected(color)
    }
    fun emitHsv() = apply(Color.hsv(hue, sat, bright), rederive = false)

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // ── Hue/saturation disc ──
        var center by remember { mutableStateOf(Offset.Zero) }
        var radiusPx by remember { mutableStateOf(1f) }
        fun pick(offset: Offset) {
            val dx = offset.x - center.x
            val dy = offset.y - center.y
            val degrees = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat().let { if (it < 0) it + 360f else it }
            hue = degrees
            sat = kotlin.math.min(1f, kotlin.math.sqrt(dx * dx + dy * dy) / radiusPx)
            emitHsv()
        }
        Canvas(
            modifier = Modifier
                .size(discSize)
                .pointerInput(Unit) { detectTapGestures { pick(it) } }
                .pointerInput(Unit) { detectDragGestures { change, _ -> pick(change.position) } },
        ) {
            center = Offset(size.width / 2, size.height / 2)
            radiusPx = size.minDimension / 2
            val hues = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
            drawCircle(brush = Brush.sweepGradient(hues, center), radius = radiusPx, center = center)
            // White core → saturation falls toward the center.
            drawCircle(
                brush = Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, radiusPx),
                radius = radiusPx,
                center = center,
            )
            // Selector ring at the current hue/sat position, filled with the actual color.
            val ang = Math.toRadians(hue.toDouble())
            val selPos = Offset(
                center.x + (sat * radiusPx * kotlin.math.cos(ang)).toFloat(),
                center.y + (sat * radiusPx * kotlin.math.sin(ang)).toFloat(),
            )
            drawCircle(Color.hsv(hue, sat, bright), radius = 9.dp.toPx(), center = selPos)
            drawCircle(Color.White, radius = 9.dp.toPx(), center = selPos, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
        Spacer(Modifier.height(10.dp))

        // ── Brightness ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Brightness", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Slider(
                value = bright,
                onValueChange = { bright = it; emitHsv() },
                valueRange = 0.15f..1f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = Color.hsv(hue, sat, bright),
                    activeTrackColor = Color.hsv(hue, sat, 1f),
                ),
            )
        }

        // ── Exact color: hex in, swatches for quick starts ──
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = hexText,
                onValueChange = { raw ->
                    val filtered = raw.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(6).uppercase()
                    hexText = filtered
                    if (filtered.length == 6) {
                        filtered.toLongOrNull(16)?.let { apply(Color(0xFF000000L or it), rederive = true) }
                    }
                },
                prefix = { Text("#", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 13.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                modifier = Modifier
                    .width(118.dp)
                    .onFocusChanged { hexFocused = it.isFocused },
            )
            SWATCHES.forEach { swatch ->
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(swatch)
                        .pointerInput(swatch) { detectTapGestures { apply(swatch, rederive = true) } },
                )
            }
        }
    }
}
