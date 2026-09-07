package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.core.model.Fleet
import chat.keryx.core.model.GatewayEntry

/**
 * The fleet's surfaces (2.11): the Desktop's `Settings → Gateways` registry, its add-connection
 * editor, and the sidebar's gateway selector — on a phone.
 *
 * Three composables, one rule between them: the workspace lives on exactly one gateway, and
 * moving it is a relaunch (the door toggle's own path — the spine is built at startup). So a
 * switch here never pretends to be live: it commits and hands the caller a relaunch, and the
 * app comes back up on the row that was chosen.
 */

/** The registry: every gateway, its pills, and the verbs the Desktop gives a row. */
@Composable
fun GatewayRegistry(
    viewModel: ChatViewModel,
    /** The workspace moved to another gateway: the process must come back up on it. */
    onRelaunch: () -> Unit,
) {
    val fleet by viewModel.fleet.collectAsState()
    var renaming by remember { mutableStateOf<GatewayEntry?>(null) }
    var removing by remember { mutableStateOf<GatewayEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    // The Test verdict, per row, until the next tap — the Desktop's "Reachable" toast, kept
    // where the row is so the answer and the question sit together.
    var verdicts by remember { mutableStateOf<Map<String, Pair<Boolean, String>>>(emptyMap()) }
    var testing by remember { mutableStateOf<Set<String>>(emptySet()) }

    Text(
        text = "Every Hermes gateway this phone can reach. Sessions, jobs, bots and the Hub are the active gateway's; switching moves the whole workspace.",
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        fontSize = 12.sp,
    )
    Spacer(Modifier.height(10.dp))

    fleet.gateways.forEach { entry ->
        val active = entry.id == fleet.activeId
        val primary = entry.id == fleet.primaryId
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(KeryxRadius.chip))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (active) 0.55f else 0.28f))
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = entry.name,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (active) FleetPill("Active", filled = true)
                        if (primary) FleetPill("Primary", filled = false)
                    }
                    Text(
                        text = entry.hostLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) {
                        if (entry.id in testing) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(KeryxGlyphs.Kebab, contentDescription = "Gateway actions", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (!active) DropdownMenuItem(
                            text = { Text("Switch to") },
                            onClick = { menu = false; if (viewModel.switchGateway(entry.id)) onRelaunch() },
                        )
                        DropdownMenuItem(
                            text = { Text("Test") },
                            onClick = {
                                menu = false
                                testing = testing + entry.id
                                viewModel.testGateway(entry.id) { ok, message ->
                                    testing = testing - entry.id
                                    verdicts = verdicts + (entry.id to (ok to message))
                                }
                            },
                        )
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = entry })
                        if (!primary) DropdownMenuItem(
                            text = { Text("Make primary") },
                            onClick = { menu = false; viewModel.setPrimaryGateway(entry.id) },
                        )
                        if (!active) DropdownMenuItem(
                            text = { Text("Remove", color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; removing = entry },
                        )
                    }
                }
            }
            verdicts[entry.id]?.let { (ok, message) ->
                Text(
                    text = message,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { adding = true }) {
        Icon(KeryxGlyphs.Plug, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Add gateway")
    }
    if (fleet.size == 1) Text(
        text = "Primary is the fallback when the gateway you were on is gone. Make primary never switches the workspace.",
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 8.dp),
    )

    renaming?.let { entry ->
        var name by remember(entry.id) { mutableStateOf(entry.name) }
        var error by remember(entry.id) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename gateway") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; error = null },
                        label = { Text("Name") },
                        singleLine = true,
                        isError = error != null,
                        supportingText = { error?.let { Text(it) } ?: Text("Shown wherever this gateway appears") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val problem = viewModel.renameGateway(entry.id, name)
                    if (problem == null) renaming = null else error = problem
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    removing?.let { entry ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${entry.name}?") },
            text = {
                Text(
                    "Its sign-in, read-marks, cached transcripts and archive leave this phone. " +
                        "The gateway itself is not touched — you can add it again any time.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeGateway(entry.id)?.let { viewModel.toast(it) }
                    removing = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }

    if (adding) AddGatewaySheet(
        viewModel = viewModel,
        onDismiss = { adding = false },
        onConnected = { needsRelaunch -> adding = false; if (needsRelaunch) onRelaunch() },
    )
}

@Composable
private fun FleetPill(label: String, filled: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    Text(
        text = label,
        color = if (filled) MaterialTheme.colorScheme.onPrimary else accent,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .padding(start = 8.dp)
            .clip(RoundedCornerShape(50))
            .then(if (filled) Modifier.background(accent) else Modifier.border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(50)))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/**
 * The add-connection editor: a name, the gateway's URL, and — for an ungated gateway — its
 * session token. A gated gateway signs in through the system browser, exactly as the login
 * screen does; the sheet simply waits. On success the new gateway is the active one and the
 * caller relaunches onto it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddGatewaySheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onConnected: (needsRelaunch: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    KeryxSheet(onDismiss = { if (!connecting) onDismiss() }, title = "Add gateway", sheetState = sheetState) {
        Text(
            text = "A running hermes dashboard, reachable from this phone — over the LAN, Tailscale or the internet.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; error = null },
            label = { Text("Name") },
            placeholder = { Text("Homelab") },
            supportingText = { Text("Required, unique — shown wherever this gateway appears") },
            singleLine = true,
            enabled = !connecting,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it; error = null },
            label = { Text("Gateway URL") },
            placeholder = { Text("http://your-host:9119") },
            singleLine = true,
            enabled = !connecting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it; error = null },
            label = { Text("Session token — ungated gateways only") },
            supportingText = { Text("A gated gateway signs in through your browser instead") },
            singleLine = true,
            enabled = !connecting,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        Button(
            onClick = {
                error = null
                connecting = true
                viewModel.loginToGateway(
                    url.trim(), key.trim(),
                    launchBrowser = { authorize ->
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(authorize)),
                        )
                    },
                    onResult = { ok, needsRestart, message ->
                        connecting = false
                        if (ok) onConnected(needsRestart) else error = message ?: "Could not connect"
                    },
                    name = name.trim(),
                )
            },
            enabled = !connecting && url.isNotBlank() && name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp).padding(top = 8.dp),
        ) {
            if (connecting) CircularProgressIndicator(modifier = Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            else Text("Connect", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The sidebar's gateway selector (Desktop: "with several gateways, the sidebar shows one named
 * gateway selector"). Absent with one gateway — a single-gateway drawer keeps its old shape.
 */
@Composable
fun FleetSelector(
    fleet: Fleet,
    onSwitch: (String) -> Unit,
    onManage: () -> Unit,
) {
    if (!fleet.hasChoice) return
    var open by remember { mutableStateOf(false) }
    val active = fleet.active ?: return
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClickLabel = "Switch gateway") { open = true }
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Icon(
                KeryxGlyphs.Plug,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = active.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                KeryxGlyphs.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fleet.gateways.forEach { entry ->
                val isActive = entry.id == fleet.activeId
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                entry.name,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(entry.hostLabel, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { open = false; if (!isActive) onSwitch(entry.id) },
                )
            }
            DropdownMenuItem(
                text = { Text("Manage gateways…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = { open = false; onManage() },
            )
        }
    }
}
