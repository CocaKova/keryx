package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.keryx.app.R
import chat.keryx.app.presentation.AgentDelegate
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.transport.direct.AgentPlugin
import chat.keryx.app.transport.direct.McpAuthStyle
import chat.keryx.app.transport.direct.PluginCatalogEntry

// --- Plugins (3.0) -----------------------------------------------------------------------------

/**
 * The agent's plugins: the ones you installed first, each with its switch; the ones Hermes ships
 * folded away underneath (there are dozens: every provider and chat platform is one); then the
 * catalog and a Git install. A plugin runs on the gateway, so changes land on its next restart.
 */
@Composable
internal fun PluginsTab(viewModel: ChatViewModel) {
    val panel by viewModel.agent.plugins.collectAsState()
    val catalog by viewModel.agent.pluginCatalog.collectAsState()
    LaunchedEffect(Unit) { viewModel.agent.refreshPluginCatalog() }
    var showBundled by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<AgentPlugin?>(null) }
    var installing by remember { mutableStateOf<PluginCatalogEntry?>(null) }
    var fromGit by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PanelErrorLine(panel.error)
        val all = panel.data
        if (all == null) {
            if (panel.error == null) PanelLoading()
            return@Column
        }
        val yours = all.filterNot { it.bundled }.sortedBy { it.name.lowercase() }
        val bundled = all.filter { it.bundled }.sortedBy { it.name.lowercase() }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            item(key = "plugins-yours") {
                if (yours.isEmpty()) Text(
                    stringResource(R.string.agent_plugins_empty),
                    fontSize = KeryxType.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 20.dp),
                ) else KeryxHubGroup(
                    stringResource(R.string.agent_plugins_yours, yours.count { it.enabled }, yours.size),
                    modifier = Modifier.animateContentSize(KeryxMotion.settleSize),
                ) {
                    yours.forEachIndexed { i, p ->
                        if (i > 0) KeryxHubDivider()
                        PluginRow(
                            p,
                            onToggle = { viewModel.agent.setPluginEnabled(p.name, it) },
                            onRemove = if (p.canRemove) ({ removing = p }) else null,
                        )
                    }
                }
            }
            item(key = "plugins-note") {
                Text(
                    stringResource(R.string.agent_plugins_restart_note),
                    fontSize = KeryxType.micro,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp),
                )
            }
            if (bundled.isNotEmpty()) item(key = "plugins-bundled") {
                Column(Modifier.animateContentSize(KeryxMotion.settleSize)) {
                    TextButton(onClick = { showBundled = !showBundled }, modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            stringResource(
                                if (showBundled) R.string.agent_plugins_bundled_hide else R.string.agent_plugins_bundled_show,
                                bundled.size,
                            ),
                            fontSize = KeryxType.caption,
                        )
                    }
                    if (showBundled) KeryxHubGroup {
                        bundled.forEachIndexed { i, p ->
                            if (i > 0) KeryxHubDivider()
                            PluginRow(p, onToggle = { viewModel.agent.setPluginEnabled(p.name, it) }, onRemove = null)
                        }
                    }
                }
            }
            item(key = "plugins-git") {
                TextButton(onClick = { fromGit = true }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.agent_plugin_install_git), fontSize = KeryxType.body)
                }
            }
            item(key = "plugins-catalog") {
                val entries = catalog.data
                when {
                    entries == null -> if (catalog.error == null) {
                        KeryxSkeleton(rows = 3, modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp))
                    } else PanelErrorLine(catalog.error)
                    entries.isNotEmpty() -> KeryxHubGroup(
                        stringResource(R.string.agent_plugins_catalog, entries.count { !it.installed }),
                        modifier = Modifier.animateContentSize(KeryxMotion.settleSize),
                    ) {
                        entries.forEachIndexed { i, e ->
                            if (i > 0) KeryxHubDivider()
                            PluginCatalogRow(e) { installing = e }
                        }
                    }
                }
            }
        }
    }
    removing?.let { p ->
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            shape = RoundedCornerShape(KeryxRadius.sheet),
            onDismissRequest = { if (!busy) removing = null },
            title = { Text(stringResource(R.string.agent_plugin_remove_title, p.name), fontSize = KeryxType.titleLarge) },
            text = { Text(stringResource(R.string.agent_plugin_remove_body), fontSize = KeryxType.body) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    viewModel.agent.removePlugin(p.name) { busy = false; removing = null }
                }) { Text(stringResource(R.string.agent_plugin_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { removing = null }) { Text(stringResource(R.string.mk_cancel)) } },
        )
    }
    if (fromGit || installing != null) {
        PluginInstallDialog(entry = installing, viewModel = viewModel) { fromGit = false; installing = null }
    }
}

@Composable
private fun PluginRow(p: AgentPlugin, onToggle: (Boolean) -> Unit, onRemove: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p.name, fontSize = KeryxType.body, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (p.version.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(p.version, fontSize = KeryxType.micro, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (p.description.isNotBlank()) Text(
                    p.description,
                    fontSize = KeryxType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                p.authCommand?.let {
                    Text(
                        stringResource(R.string.agent_plugin_signin_hint, it),
                        fontSize = KeryxType.micro,
                        color = KeryxStatus.warn,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                p.removedReason?.let {
                    Text(
                        stringResource(R.string.agent_plugin_pulled, it),
                        fontSize = KeryxType.micro,
                        color = KeryxStatus.bad,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Switch(checked = p.enabled, onCheckedChange = onToggle)
        }
        if (onRemove != null) {
            TextButton(onClick = onRemove, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(stringResource(R.string.agent_plugin_remove), fontSize = KeryxType.caption, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun PluginCatalogRow(e: PluginCatalogEntry, onInstall: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.title, fontSize = KeryxType.body, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Text(e.tier, fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (e.description.isNotBlank()) Text(
                e.description, fontSize = KeryxType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        if (e.installed) Text(stringResource(R.string.agent_plugin_installed_badge), fontSize = KeryxType.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else TextButton(onClick = onInstall) { Text(stringResource(R.string.agent_plugin_install)) }
    }
}

/** Install one: a catalog [entry] (reviewed, pinned by the catalog) or, with none, from Git. */
@Composable
private fun PluginInstallDialog(entry: PluginCatalogEntry?, viewModel: ChatViewModel, onDismiss: () -> Unit) {
    var source by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val title = entry?.title ?: stringResource(R.string.agent_plugin_install_git)
    AlertDialog(
        shape = RoundedCornerShape(KeryxRadius.sheet),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title, fontSize = KeryxType.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (entry != null) {
                    if (entry.description.isNotBlank()) Text(entry.description, fontSize = KeryxType.body)
                    if (entry.repo.isNotBlank()) Text(entry.repo, fontSize = KeryxType.caption, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (entry.requiresEnv.isNotEmpty()) Text(
                        stringResource(R.string.agent_plugin_needs_env, entry.requiresEnv.joinToString(", ")),
                        fontSize = KeryxType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    OutlinedTextField(
                        value = source,
                        onValueChange = { source = it; problem = null },
                        label = { Text(stringResource(R.string.agent_plugin_git_hint)) },
                        singleLine = true,
                        enabled = !busy,
                        shape = RoundedCornerShape(KeryxRadius.field),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(stringResource(R.string.agent_plugin_git_note), fontSize = KeryxType.micro,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                problem?.let { Text(it, fontSize = KeryxType.caption, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (entry != null || source.isNotBlank()),
                onClick = {
                    busy = true
                    viewModel.agent.installPlugin(
                        identifier = if (entry == null) source else null,
                        catalogName = entry?.name,
                    ) { outcome ->
                        busy = false
                        when (outcome) {
                            is AgentDelegate.PluginInstallOutcome.Installed -> {
                                val r = outcome.result
                                val name = r.name ?: entry?.name ?: source
                                val head = context.getString(
                                    if (r.restartRequired) R.string.agent_plugin_installed_restart else R.string.agent_plugin_installed,
                                    name,
                                )
                                val env = r.missingEnv.takeIf { it.isNotEmpty() }
                                    ?.let { " " + context.getString(R.string.agent_plugin_needs_env, it.joinToString(", ")) }.orEmpty()
                                viewModel.toast(head + env)
                                onDismiss()
                            }
                            is AgentDelegate.PluginInstallOutcome.Refused -> problem =
                                context.getString(R.string.agent_plugin_install_failed, outcome.message)
                            AgentDelegate.PluginInstallOutcome.Unsupported -> { viewModel.toast(context.getString(R.string.agent_plugin_unsupported)); onDismiss() }
                        }
                    }
                },
            ) { Text(stringResource(if (busy) R.string.agent_plugin_installing else R.string.agent_plugin_install)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.mk_cancel)) } },
    )
}

// --- An MCP server by URL (3.0) ----------------------------------------------------------------

/**
 * Add a server that lives at a URL, with the sign-in it uses: none, a token (sent as a Bearer
 * header; the gateway keeps it in its .env, never in config), or OAuth, where the server's own
 * sign-in page opens in the browser and the gateway receives the grant.
 */
@Composable
internal fun McpAddDialog(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var auth by rememberSaveable { mutableStateOf(McpAuthStyle.NONE.name) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val style = McpAuthStyle.valueOf(auth)
    val urlOk = url.trim().let { it.startsWith("https://") || it.startsWith("http://") }
    val ready = name.isNotBlank() && urlOk && (style != McpAuthStyle.TOKEN || token.isNotBlank())
    AlertDialog(
        shape = RoundedCornerShape(KeryxRadius.sheet),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.agent_mcp_add_title), fontSize = KeryxType.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(64); problem = null },
                    label = { Text(stringResource(R.string.agent_mcp_add_name)) },
                    singleLine = true, enabled = !busy, shape = RoundedCornerShape(KeryxRadius.field),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url, onValueChange = { url = it; problem = null },
                    label = { Text(stringResource(R.string.agent_mcp_add_url_field)) },
                    placeholder = { Text("https://", fontFamily = FontFamily.Monospace) },
                    singleLine = true, enabled = !busy, shape = RoundedCornerShape(KeryxRadius.field),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.agent_mcp_add_auth), fontSize = KeryxType.caption, fontWeight = FontWeight.SemiBold)
                KeryxSegmented(
                    options = listOf(
                        McpAuthStyle.NONE.name to stringResource(R.string.agent_mcp_auth_none),
                        McpAuthStyle.TOKEN.name to stringResource(R.string.agent_mcp_auth_token),
                        McpAuthStyle.OAUTH.name to stringResource(R.string.agent_mcp_auth_oauth),
                    ),
                    selected = auth,
                    onSelect = { auth = it; problem = null },
                )
                when (style) {
                    McpAuthStyle.TOKEN -> {
                        OutlinedTextField(
                            value = token, onValueChange = { token = it },
                            label = { Text(stringResource(R.string.agent_mcp_add_token)) },
                            singleLine = true, enabled = !busy, shape = RoundedCornerShape(KeryxRadius.field),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(R.string.agent_mcp_add_token_note), fontSize = KeryxType.micro,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    McpAuthStyle.OAUTH -> Text(stringResource(R.string.agent_mcp_add_oauth_note), fontSize = KeryxType.micro,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    McpAuthStyle.NONE -> Unit
                }
                problem?.let { Text(it, fontSize = KeryxType.caption, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = ready && !busy, onClick = {
                busy = true
                val shown = name.trim()
                viewModel.agent.addMcpByUrl(shown, url, style, token) { outcome ->
                    busy = false
                    when (outcome) {
                        AgentDelegate.McpAddOutcome.Added -> { viewModel.toast(context.getString(R.string.agent_mcp_added, shown)); onDismiss() }
                        is AgentDelegate.McpAddOutcome.SignIn -> {
                            viewModel.toast(context.getString(R.string.agent_mcp_signin_opening, shown))
                            runCatching { uri.openUri(outcome.url) }
                            onDismiss()
                        }
                        is AgentDelegate.McpAddOutcome.Refused -> problem = context.getString(R.string.agent_mcp_add_failed, shown, outcome.message)
                        AgentDelegate.McpAddOutcome.Unsupported -> { viewModel.toast(context.getString(R.string.agent_plugin_unsupported)); onDismiss() }
                    }
                }
            }) { Text(stringResource(R.string.agent_mcp_add)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.mk_cancel)) } },
    )
}
