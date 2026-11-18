package com.astra.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.astra.agent.hub.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
private fun PluginCard(vm: AstraViewModel, p: Plugin, onUse: ((Plugin) -> Unit)?) {
    val c = LocalIos.current
    val tick by PluginHub.changed.collectAsState()
    var open by remember(p.id) { mutableStateOf(false) }
    var busy by remember(p.id) { mutableStateOf(false) }
    var msg by remember(p.id) { mutableStateOf("") }
    val values = remember(p.id) { mutableStateMapOf<String, String>() }
    val connected = remember(tick) { PluginHub.isConnected(p.id) }
    val enabled = remember(tick) { PluginHub.isEnabled(p.id) }
    val health = remember(tick) { PluginHub.healthText(p.id) }
    val planned = p.impl == Impl.PLANNED

    HubCard(Modifier.pressScale { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HubDot(if (planned) c.sub else if (connected && enabled) c.green else if (connected) c.orange else c.line)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(p.provider + " \u00B7 " + p.category + (if (!planned) " \u00B7 " + p.auth.label else ""), fontSize = 11.sp, color = c.sub)
            }
            HubBadge(if (planned) "Planned" else if (!connected) "Not connected" else if (!enabled) "Disabled" else "Connected", if (planned) c.sub else if (connected && enabled) c.green else c.orange)
        }
        Text(p.description, fontSize = 13.sp, color = c.sub, modifier = Modifier.padding(top = 4.dp))
        if (connected && health.isNotEmpty()) Text(health, fontSize = 12.sp, color = if (health.startsWith("OK")) c.green else c.danger)
        if (open) {
            Spacer(Modifier.height(8.dp))
            if (planned) {
                Text("Not implemented yet. It is on the roadmap and cannot be connected in this version.", fontSize = 13.sp, color = c.orange)
            } else {
                if (p.actions.isNotEmpty()) {
                    Text("Actions", fontSize = 12.sp, color = c.accent, fontWeight = FontWeight.SemiBold)
                    p.actions.forEach { a -> Text("\u2022 ${a.id} (${a.riskLabel}): ${a.desc}", fontSize = 12.sp) }
                }
                if (p.permissions.isNotEmpty()) { Text("Permissions", fontSize = 12.sp, color = c.accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp)); Text(p.permissions.joinToString("; "), fontSize = 12.sp) }
                Text("Endpoints: ${p.endpoints}", fontSize = 11.sp, color = c.sub, modifier = Modifier.padding(top = 6.dp))
                Text("Verification: ${p.verification}", fontSize = 11.sp, color = c.sub)
                Text("v${p.version}", fontSize = 11.sp, color = c.sub)
                if (!connected) {
                    Text("How to connect", fontSize = 12.sp, color = c.accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    Text(p.setupHelp, fontSize = 12.sp)
                    p.fields.forEach { f ->
                        HubField(values[f.key] ?: "", { values[f.key] = it }, f.label + (if (f.optional) " (optional)" else ""), secret = f.secret)
                        if (f.hint.isNotEmpty()) Text(f.hint, fontSize = 11.sp, color = c.sub)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        HubButton(if (busy) "Working\u2026" else if (p.auth == AuthType.OAUTH) "Sign in" else "Connect", enabled = !busy) {
                            busy = true; msg = ""
                            val snapshot = values.toMap()
                            vm.viewModelScope.launch(Dispatchers.Default) {
                                msg = try { (if (p.auth == AuthType.OAUTH) PluginHub.connectOAuth(p.id, snapshot) else PluginHub.connectWithFields(p.id, snapshot)).ifEmpty { "Connected." } }
                                      catch (e: Exception) { "Failed: ${e.message}" }
                                if (msg == "Connected.") values.clear()
                                busy = false
                            }
                        }
                        if (busy && p.auth == AuthType.OAUTH) HubButton("Cancel", c.danger) { OAuth.cancel() }
                    }
                    if (p.auth == AuthType.OAUTH && busy) Text("Finish the sign-in in your browser, then come back to ZENIQ.", fontSize = 12.sp, color = c.sub)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Enabled", Modifier.weight(1f), fontSize = 14.sp)
                        HubSwitch(enabled) { PluginHub.setEnabled(p.id, it) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        HubButton("Check health", enabled = !busy) { busy = true; vm.viewModelScope.launch(Dispatchers.Default) { msg = PluginHub.checkHealth(p.id); busy = false } }
                        HubButton("Disconnect", c.danger) { vm.viewModelScope.launch(Dispatchers.Default) { PluginHub.disconnect(p.id); msg = "Disconnected. Your credentials were removed from this phone." } }
                        if (onUse != null) HubButton("Use in chat", c.green) { onUse(p) }
                    }
                }
                if (p.id.startsWith("custom_")) HubButton("Remove plugin", c.danger, modifier = Modifier.padding(top = 6.dp)) { PluginHub.removeCustom(p.id) }
                if (msg.isNotEmpty()) Text(msg, fontSize = 13.sp, color = if (msg.startsWith("Connected") || msg.startsWith("OK") || msg.startsWith("Disconnected")) c.green else c.danger, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** The plugin manager: discover, installed, activity, permissions/settings and custom plugins. */
@Composable
fun PluginManagerPane(vm: AstraViewModel, onUse: ((Plugin) -> Unit)? = null) {
    val c = LocalIos.current
    val tick by PluginHub.changed.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("All") }
    val plugins = remember(tick) { PluginHub.all() }
    val activity = remember(tick) { PluginHub.activity() }
    val q = query.trim().lowercase()

    Column(Modifier.fillMaxSize()) {
        HubChips(listOf("Discover", "Installed", "Activity", "Settings", "Create"), tab) { tab = it }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            when (tab) {
                0 -> {
                    item { HubField(query, { query = it }, "Search plugins", modifier = Modifier.padding(horizontal = 16.dp)) }
                    item {
                        val cats = listOf("All") + plugins.map { it.category }.distinct()
                        HubChips(cats, cats.indexOf(cat).coerceAtLeast(0)) { cat = cats[it] }
                    }
                    val list = plugins.filter { (cat == "All" || it.category == cat) && (q.isEmpty() || it.name.lowercase().contains(q) || it.description.lowercase().contains(q)) }
                    items(list, key = { it.id }) { PluginCard(vm, it, onUse) }
                }
                1 -> {
                    val inst = plugins.filter { PluginHub.isConnected(it.id) }
                    if (inst.isEmpty()) item { Text("No account is connected yet. Open Discover, pick a service and connect it. ZENIQ then uses it automatically when a request needs it.", Modifier.padding(20.dp), color = c.sub, fontSize = 14.sp) }
                    items(inst, key = { it.id }) { PluginCard(vm, it, onUse) }
                }
                2 -> {
                    val log = activity
                    if (log.isEmpty()) item { Text("No plugin activity yet.", Modifier.padding(20.dp), color = c.sub, fontSize = 14.sp) }
                    items(log) { a ->
                        HubCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text((PluginHub.plugin(a.plugin)?.name ?: a.plugin) + " \u00B7 " + a.action, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                HubBadge(if (a.ok) "ok" else "failed", if (a.ok) c.green else c.danger)
                            }
                            if (a.summary.isNotEmpty()) Text(a.summary, fontSize = 11.sp, color = c.sub)
                            if (a.detail.isNotEmpty()) Text(a.detail.take(160), fontSize = 12.sp, color = c.sub)
                            Text(hubTime(a.ts), fontSize = 11.sp, color = c.sub)
                        }
                    }
                    if (log.isNotEmpty()) item { Box(Modifier.padding(start = 16.dp, top = 6.dp)) { HubButton("Clear activity", c.danger) { PluginHub.clearActivity() } } }
                }
                3 -> item {
                    var level by remember(tick) { mutableIntStateOf(PluginHub.confirmLevel) }
                    HubCard {
                        Text("Permissions", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("When should ZENIQ ask before a plugin acts?", fontSize = 13.sp, color = c.sub)
                        Spacer(Modifier.height(6.dp))
                        HubSeg(listOf("Never", "Send & delete", "Every change"), level) { level = it; PluginHub.confirmLevel = it }
                        Text(when (level) { 0 -> "ZENIQ acts without asking. Only use this for accounts you are comfortable with."; 1 -> "Recommended: reading and drafting are automatic; sending messages or emails and other irreversible actions ask first."; else -> "ZENIQ asks before anything that changes data, such as creating events, issues or pages." }, fontSize = 12.sp, color = c.sub)
                    }
                    HubCard {
                        Text("Security", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("\u2022 Tokens are encrypted with a key in the Android Keystore and never leave this phone except to the service itself.\n\u2022 No keys or secrets are built into the app: you bring your own.\n\u2022 Text from emails, pages and documents is treated as data, never as instructions.\n\u2022 Disconnect removes the stored credentials and revokes Google access.", fontSize = 12.sp, color = c.sub)
                    }
                }
                else -> item { CustomPluginForm() }
            }
        }
    }
}

@Composable
private fun CustomPluginForm() {
    val c = LocalIos.current
    var name by remember { mutableStateOf("") }
    var base by remember { mutableStateOf("https://") }
    var header by remember { mutableStateOf("Authorization") }
    var prefix by remember { mutableStateOf("Bearer ") }
    var msg by remember { mutableStateOf("") }
    HubCard {
        Text("Create a custom plugin", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("Connect any REST API. ZENIQ sends your token in the header you choose and can call paths under the base URL.", fontSize = 12.sp, color = c.sub)
        HubField(name, { name = it }, "Name")
        HubField(base, { base = it }, "Base URL (https://...)")
        HubField(header, { header = it }, "Header name")
        HubField(prefix, { prefix = it }, "Header prefix (e.g. Bearer )")
        HubButton("Add plugin") { msg = PluginHub.addCustom(name, base, header, prefix).ifEmpty { "Added. Open Discover to connect it with your token."; }; if (msg.startsWith("Added")) name = "" }
        if (msg.isNotEmpty()) Text(msg, fontSize = 13.sp, color = c.accent, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Full-screen Plugins page opened from the + button in the chat. */
@Composable
fun PluginsOverlay(vm: AstraViewModel, onClose: () -> Unit, onUse: (Plugin) -> Unit) {
    val c = LocalIos.current
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = c.accent) }
            Column {
                Text("Plugins", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Connect services. ZENIQ picks the right one for each request.", fontSize = 12.sp, color = c.sub)
            }
        }
        PluginManagerPane(vm, onUse)
    }
}
