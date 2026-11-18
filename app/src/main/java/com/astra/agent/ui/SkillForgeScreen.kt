package com.astra.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.astra.agent.hub.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun SkillCard(vm: AstraViewModel, s: Skill, onChanged: () -> Unit) {
    val c = LocalIos.current
    var open by remember(s.id) { mutableStateOf(false) }
    val av = remember(s.id, open) { SkillEngine.availability(s, vm.tools) }
    HubCard(Modifier.pressScale { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HubDot(if (av.ready) c.green else c.orange)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(s.category + (if (s.custom) " \u00B7 yours" else "") + " \u00B7 v" + s.version, fontSize = 11.sp, color = c.sub)
            }
            HubBadge(if (av.ready) "Ready" else "Needs setup", if (av.ready) c.green else c.orange)
        }
        Text(s.description, fontSize = 13.sp, color = c.sub, modifier = Modifier.padding(top = 4.dp))
        if (!av.ready) Text(av.note, fontSize = 12.sp, color = c.orange)
        if (s.dependencies.isNotEmpty()) Text("Works with: " + s.dependencies.joinToString { SkillEngine.get(it)?.name ?: it }, fontSize = 12.sp, color = c.sub)
        if (open) {
            Spacer(Modifier.height(8.dp))
            Text("Instructions", fontSize = 12.sp, color = c.accent, fontWeight = FontWeight.SemiBold)
            Text(s.instructions, fontSize = 13.sp)
            if (s.steps.isNotEmpty()) { Text("Execution steps", fontSize = 12.sp, color = c.accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp)); Text(s.steps.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n"), fontSize = 13.sp) }
            if (s.verification.isNotEmpty()) { Text("Verification", fontSize = 12.sp, color = c.accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp)); Text(s.verification.joinToString("\n") { "\u2713 $it" }, fontSize = 13.sp) }
            if (s.tools.isNotEmpty()) Text("Tools: " + s.tools.joinToString(), fontSize = 12.sp, color = c.sub, modifier = Modifier.padding(top = 6.dp))
            if (s.permissions.isNotEmpty()) Text("Permissions: " + s.permissions.joinToString(), fontSize = 12.sp, color = c.sub)
            Text("Input: ${s.inputSchema}  \u00B7  Output: ${s.outputSchema}", fontSize = 11.sp, color = c.sub)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                HubButton("Use in AutoPilot") { AutoSession.request.value = "Use the ${s.name} skill: " }
                if (s.custom) HubButton("Delete", c.danger) { SkillEngine.deleteCustom(s.id); onChanged() }
            }
        }
    }
}

@Composable
fun SkillForgePane(vm: AstraViewModel) {
    val c = LocalIos.current
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    val all = remember(version) { SkillEngine.all() }
    var active by remember { mutableStateOf(SkillEngine.active) }
    LaunchedEffect(tab) { while (tab == 3) { active = SkillEngine.active; delay(800) } }

    Column(Modifier.fillMaxSize()) {
        HubChips(listOf("Essential", "All", "My Skills", "Active", "History", "Create"), tab) { tab = it }
        if (tab == 0 || tab == 1 || tab == 2) HubField(query, { query = it }, "Search skills", modifier = Modifier.padding(horizontal = 16.dp))
        val q = query.trim().lowercase()
        fun match(s: Skill) = q.isEmpty() || s.name.lowercase().contains(q) || s.description.lowercase().contains(q) || s.category.lowercase().contains(q) || s.keywords.any { it.contains(q) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            when (tab) {
                0 -> {
                    item { Text("${SkillCatalog.all.size} built-in skills. You never pick them yourself: ZENIQ chooses the right ones for every request.", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), fontSize = 12.sp, color = c.sub) }
                    val list = SkillCatalog.all.filter { match(it) }
                    list.groupBy { it.category }.forEach { (cat, skills) ->
                        item(key = "h_$cat") { SectionHeader(cat) }
                        items(skills, key = { it.id }) { SkillCard(vm, it) { version++ } }
                    }
                }
                1 -> items(all.filter { match(it) }, key = { it.id }) { SkillCard(vm, it) { version++ } }
                2 -> {
                    val mine = all.filter { it.custom && match(it) }
                    if (mine.isEmpty()) item { Text("Nothing here yet. Use Create to make a skill from a plain description.", Modifier.padding(20.dp), color = c.sub, fontSize = 14.sp) }
                    items(mine, key = { it.id }) { SkillCard(vm, it) { version++ } }
                }
                3 -> {
                    if (active.isEmpty()) item { Text("No skill is running right now. While ZENIQ works on a request, the skills it chose appear here.", Modifier.padding(20.dp), color = c.sub, fontSize = 14.sp) }
                    items(active.mapNotNull { SkillEngine.get(it) }, key = { it.id }) { SkillCard(vm, it) { version++ } }
                }
                4 -> {
                    val h = SkillEngine.history()
                    if (h.isEmpty()) item { Text("No executions yet.", Modifier.padding(20.dp), color = c.sub, fontSize = 14.sp) }
                    items(h) { u ->
                        HubCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(u.request.take(80), Modifier.weight(1f), fontSize = 14.sp)
                                HubBadge(u.status.replace('_', ' '), if (u.status == "completed" || u.status == "chat") c.green else if (u.status == "failed") c.danger else c.orange)
                            }
                            Text(hubTime(u.ts) + " \u00B7 " + u.skills.joinToString { SkillEngine.get(it)?.name ?: it }, fontSize = 11.sp, color = c.sub)
                        }
                    }
                    if (h.isNotEmpty()) item { Box(Modifier.padding(start = 16.dp, top = 6.dp)) { HubButton("Clear history", c.danger) { SkillEngine.clearHistory(); version++ } } }
                }
                else -> item { CreateSkill(vm) { version++; tab = 2 } }
            }
        }
    }
}

@Composable
private fun CreateSkill(vm: AstraViewModel, onDone: () -> Unit) {
    val c = LocalIos.current
    var text by remember { mutableStateOf("") }
    var impName by remember { mutableStateOf("") }
    var impText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    HubCard {
        Text("Create a skill", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("Describe in plain words what the skill should do and how. With a model loaded (or Fast Mode) ZENIQ writes the steps and checks; offline your words become the instructions.", fontSize = 12.sp, color = c.sub)
        HubField(text, { text = it }, "e.g. Write polite follow-up emails after interviews", single = false, minLines = 3)
        HubButton(if (busy) "Creating\u2026" else "Create skill", enabled = text.isNotBlank() && !busy) {
            busy = true; msg = ""
            vm.viewModelScope.launch(Dispatchers.Default) {
                try { val s = SkillEngine.createFromText(text, vm.settings.value); msg = "Created '${s.name}'."; text = ""; onDone() }
                catch (e: Exception) { msg = "Could not create the skill: ${e.message}" }
                finally { busy = false }
            }
        }
        if (msg.isNotEmpty()) Text(msg, fontSize = 13.sp, color = c.accent, modifier = Modifier.padding(top = 6.dp))
    }
    HubCard {
        Text("Import instructions", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("Pasted skills are treated as untrusted text: lines that try to override rules or reveal keys are removed.", fontSize = 12.sp, color = c.sub)
        HubField(impName, { impName = it }, "Skill name")
        HubField(impText, { impText = it }, "Instructions", single = false, minLines = 4)
        HubButton("Import", enabled = impText.isNotBlank()) { val s = SkillEngine.importFromText(impName, impText); msg = "Imported '${s.name}'."; impName = ""; impText = ""; onDone() }
    }
}

/** Pick skills for Chat, Automate and Code at once (opened from the + menu). Search, multi-select. */
@Composable
fun SkillPickerDialog(onClose: () -> Unit) {
    val c = LocalIos.current
    val pinned by SkillEngine.pinned.collectAsState()
    var q by remember { mutableStateOf("") }
    val all = remember { SkillEngine.all() }
    val list = all.filter { q.isBlank() || it.name.lowercase().contains(q.lowercase()) || it.description.lowercase().contains(q.lowercase()) || it.category.lowercase().contains(q.lowercase()) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(c.card).padding(14.dp)) {
            Text("Skills", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(if (pinned.isEmpty()) "Nothing picked: ZENIQ chooses skills automatically." else "${pinned.size} picked. They are used in Chat, Automate and Code.", fontSize = 12.sp, color = c.sub)
            HubField(q, { q = it }, "Search skills")
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(list, key = { it.id }) { s ->
                    val on = s.id in pinned
                    Row(Modifier.fillMaxWidth().pressScale { if (on) SkillEngine.unpin(s.id) else SkillEngine.pin(s.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(s.description, fontSize = 12.sp, color = c.sub, maxLines = 2)
                        }
                        Text(if (on) "\u2713" else "", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                HubButton("Done") { onClose() }
                if (pinned.isNotEmpty()) HubButton("Clear", c.danger) { SkillEngine.clearPinned() }
            }
        }
    }
}
