package com.astra.agent.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.astra.agent.code.FileStorage
import com.astra.agent.device.AgentService
import com.astra.agent.hub.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

enum class AutoStage { IDLE, PLANNING, REVIEW, BUILD, CARD }

/** The state of the AutoPilot flow (prompt -> planning -> editable plan -> approve -> build -> result). It lives here so it survives switching tabs. */
object AutoSession {
    val request = MutableStateFlow("")
    val stage = MutableStateFlow(AutoStage.IDLE)
    val draft = MutableStateFlow<Plan?>(null)
    val rev = MutableStateFlow(0)                    // bumped on every edit so the editor redraws
    val events = MutableStateFlow<List<String>>(emptyList())
    val refining = MutableStateFlow(false)
    val run = MutableStateFlow<AutoRun?>(null)
    val message = MutableStateFlow("")
    val cardId = MutableStateFlow<String?>(null)
    /** The card's name while it is being planned; the user can edit it on the review page. */
    val name = MutableStateFlow("")
    /** Set by "Open automation" in the chat; the Automate tab opens that card. */
    val openCard = MutableStateFlow<String?>(null)
    var job: Job? = null

    fun touch() { draft.value?.edited = true; rev.value = rev.value + 1 }
}

object AutoFlow {
    /** 1. The user sent a prompt: show the planning page, build the quick plan at once, let the model refine it. */
    fun start(vm: AstraViewModel, request: String) {
        val req = request.trim()
        if (req.isEmpty()) return
        // "open YouTube every day at 8" -> the time phrase becomes the schedule, the rest is the task
        val parsed = Schedules.parse(req)
        val body = parsed?.prompt?.takeIf { it.isNotBlank() } ?: req
        AutoSession.request.value = body; AutoSession.events.value = emptyList(); AutoSession.draft.value = null
        AutoSession.message.value = ""; AutoSession.run.value = null; AutoSession.cardId.value = null
        val suggested = AutomationNames.suggest(req)
        AutoSession.name.value = suggested
        AutoSession.stage.value = AutoStage.PLANNING
        AutoSession.job?.cancel()
        AutoSession.job = vm.viewModelScope.launch(Dispatchers.Default) {
            try {
                val plan = AutoPilot.plan(body, vm.settings.value, vm.tools,
                    onEvent = { e -> AutoSession.events.value = AutoSession.events.value + e },
                    onQuick = { q ->
                        AutoSession.draft.value = q
                        val card = AutomationStore.save(suggested, body, parsed?.schedule, q)    // every prompt becomes a card
                        if (card == null) {
                            AutoSession.message.value = AutomationStore.capMessage()
                            AutoSession.stage.value = AutoStage.IDLE
                            AutoSession.job?.cancel()
                        } else {
                            AutoSession.cardId.value = card.id
                            AutoSession.name.value = card.name
                            AutoSession.refining.value = vm.settings.value.let { AutoPilot.modelAvailable(it) }
                            AutoSession.stage.value = AutoStage.REVIEW
                        }
                    })
                AutoSession.rev.value = AutoSession.rev.value + 1
                if (AutoSession.cardId.value != null && !plan.edited) AutomationStore.updatePlan(AutoSession.cardId.value!!, plan)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { AutoSession.message.value = "Could not plan: ${e.message}"; if (AutoSession.draft.value == null) AutoSession.stage.value = AutoStage.IDLE }
            finally { AutoSession.refining.value = false }
        }
    }

    fun openCard(id: String) {
        val a = AutomationStore.get(id) ?: return
        val plan = AutomationStore.plan(id) ?: AutoPilot.quickPlan(a.request).also { AutomationStore.updatePlan(id, it) }
        AutoSession.request.value = a.request; AutoSession.draft.value = plan; AutoSession.cardId.value = id
        AutoSession.name.value = a.name
        AutoSession.run.value = null; AutoSession.message.value = ""; AutoSession.stage.value = AutoStage.CARD
    }

    /** Runs a saved card straight from the list (and from a missed-run card). */
    fun runCard(vm: AstraViewModel, ctx: android.content.Context, id: String) {
        openCard(id)
        startRun(vm, ctx, false)
    }

    /** 4. Approve: check the edited plan, save it and the name on its card, and start with a test run. */
    fun approve(vm: AstraViewModel, ctx: android.content.Context) {
        val p = AutoSession.draft.value ?: return
        val problems = PlanJson.validate(p)
        if (problems.isNotEmpty()) { AutoSession.message.value = problems.first(); return }
        AutoSession.message.value = ""
        AutoSession.cardId.value?.let { id ->
            AutomationStore.updatePlan(id, p)
            val n = AutoSession.name.value.trim()
            if (n.isNotEmpty()) AutomationStore.rename(id, n)?.let { AutoSession.name.value = it }
        }
        AutoSession.stage.value = AutoStage.BUILD
        startRun(vm, ctx, true)
    }

    fun startRun(vm: AstraViewModel, ctx: android.content.Context, test: Boolean) {
        val p = AutoSession.draft.value ?: return
        if (AutoSession.run.value?.status == "running") return
        if (!test && p.steps.any { it.kind == StepKind.DEVICE } && !AutoPilot.accessibilityOn()) {
            AutoSession.message.value = "Accessibility is off. Turn it on first, then run."; return
        }
        val fresh = PlanJson.fresh(p)
        val r = AutoRun(fresh.goal, fresh, test).also { it.cardId = AutoSession.cardId.value }
        AutoSession.run.value = r; AutoSession.message.value = ""
        val app = ctx.applicationContext
        AutoSession.job = vm.viewModelScope.launch(Dispatchers.Default) {
            AgentService.start(app, "ZENIQ is automating\u2026")
            try { AutoPilot.execute(app, r, vm.settings.value, vm.tools) }
            catch (e: CancellationException) { /* stopped by the user */ }
            catch (e: Exception) { r.status = "failed"; r.finalText = "Error: ${e.message}"; r.bump() }
            finally { AgentService.stop(app) }
        }
    }

    fun openAccessibility(ctx: android.content.Context) {
        try { ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }
}

@Composable
fun AutomateScreen(vm: AstraViewModel) {
    var section by remember { mutableIntStateOf(0) }
    val stage by AutoSession.stage.collectAsState()
    val openReq by AutoSession.openCard.collectAsState()
    LaunchedEffect(openReq) { openReq?.let { AutoFlow.openCard(it); AutoSession.openCard.value = null } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            HubTitle("Automate", "Describe a task. ZENIQ plans it, you approve, it runs and checks the result.")
            HubSeg(listOf("AutoPilot", "SkillForge", "Plugins"), section) { section = it }
            Box(Modifier.weight(1f)) {
                when (section) {
                    0 -> AutoPilotHome(vm)
                    1 -> SkillForgePane(vm)
                    else -> PluginManagerPane(vm)
                }
            }
        }
        when (stage) {
            AutoStage.PLANNING -> PlanningPage()
            AutoStage.REVIEW -> PlanReviewPage(vm) { section = 2 }
            AutoStage.BUILD -> BuildPage(vm)
            AutoStage.CARD -> CardPage(vm)
            AutoStage.IDLE -> {}
        }
    }
}

@Composable
internal fun statusColor(st: String, c: IosColors): Color = when (st) {
    "completed" -> c.text; "running", "recovering" -> c.text; "failed" -> c.danger; else -> c.sub
}

private fun statusIcon(st: String) = when (st) { "completed" -> "\u2713"; "running" -> "\u25CF"; "recovering" -> "\u21BB"; "failed" -> "\u2717"; "skipped", "stopped" -> "\u2013"; else -> "\u25CB" }

@Composable
private fun AutoPilotHome(vm: AstraViewModel) {
    val c = LocalIos.current
    val ctx = LocalContext.current
    val use24h = remember { android.text.format.DateFormat.is24HourFormat(ctx) }
    val saveTick by AutomationStore.changed.collectAsState()
    val message by AutoSession.message.collectAsState()
    var text by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var sheetFor by remember { mutableStateOf<String?>(null) }
    val cards = remember(saveTick) { AutomationStore.list().sortedByDescending { it.created } }
    val runs = remember(saveTick) { AutomationStore.runs() }
    val missed = remember(saveTick) { AutomationStore.missed() }
    val shown = cards.filter { query.isBlank() || it.name.contains(query, true) || it.request.contains(query, true) }

    if (sheetFor != null) {
        val id = sheetFor!!
        val card = remember(saveTick, id) { AutomationStore.get(id) }
        if (card != null) ScheduleSheet(card.name, card.schedule, onDismiss = { sheetFor = null }) { s ->
            AutomationStore.setSchedule(id, s); sheetFor = null
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            HubCard {
                Row(verticalAlignment = Alignment.Bottom) {
                    HubField(text, { text = it }, "What should ZENIQ automate?", single = false, minLines = 2, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.padding(bottom = 6.dp).size(44.dp).clip(RoundedCornerShape(22.dp)).background(if (text.isBlank()) c.fill else c.accent)
                        .pressScale(enabled = text.isNotBlank()) { val t = text; text = ""; AutoFlow.start(vm, t) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.ArrowUpward, contentDescription = "Plan", tint = if (text.isBlank()) c.sub else c.onAccent)
                    }
                }
                if (text.isBlank()) Text("Try: open YouTube and play lofi music \u00B7 create a PowerPoint about AI \u00B7 summarize my GitHub repos", fontSize = 12.sp, color = c.sub, modifier = Modifier.padding(top = 4.dp))
                if (message.isNotEmpty()) Text(message, fontSize = 13.sp, color = c.danger, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item { AlarmHints() }
        if (missed.isNotEmpty()) {
            item { SectionHeader("Missed while ZENIQ was closed") }
            items(missed, key = { "m" + it.automationId + it.at }) { m ->
                HubCard {
                    Text(m.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Was due " + hubTime(m.at), fontSize = 12.sp, color = c.sub)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HubButton("Run now") { AutoFlow.runCard(vm, ctx, m.automationId) }
                        HubButton("Dismiss", c.sub) { AutomationStore.clearMissed(m.automationId) }
                    }
                }
            }
        }
        if (cards.isNotEmpty()) item { SectionHeader("Automations") }
        if (cards.size > 4) item {
            HubCard {
                HubField(query, { query = it }, "Search automations")
                if (query.isNotBlank() && shown.isEmpty()) Text("No automation matches \"$query\".", fontSize = 13.sp, color = c.sub)
            }
        }
        items(shown, key = { it.id }) { a ->
            HubCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.name, Modifier.weight(1f).pressScale { AutoFlow.openCard(a.id) }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (a.lastStatus.isNotEmpty()) HubBadge(a.lastStatus.replace('_', ' '), if (a.lastStatus.endsWith("failed")) c.danger else c.text)
                }
                Text(a.request.take(140), fontSize = 12.sp, color = c.sub, maxLines = 2)
                val sched = a.schedule
                Text(
                    (sched?.let { Schedules.chip(it, java.time.ZonedDateTime.now(), use24h) } ?: "No schedule") +
                        (if (a.lastRun > 0) " \u00B7 last run " + hubTime(a.lastRun) else ""),
                    fontSize = 11.sp, color = if (sched?.enabled == true) c.text else c.sub, modifier = Modifier.padding(top = 2.dp)
                )
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HubButton("Run") { AutoFlow.runCard(vm, ctx, a.id) }
                    HubButton(if (sched == null) "Schedule" else "Edit schedule") { sheetFor = a.id }
                    Spacer(Modifier.weight(1f))
                    if (sched != null) HubSwitch(sched.enabled, { AutomationStore.setEnabled(a.id, it) })
                }
            }
        }
        if (runs.isNotEmpty()) {
            item { SectionHeader("History") }
            items(runs.take(8)) { h ->
                HubCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(h.request.take(90), Modifier.weight(1f), fontSize = 14.sp)
                        HubBadge(h.status.replace('_', ' ') + (if (h.test) " (test)" else ""), if (h.status == "failed") c.danger else c.text)
                    }
                    Text(hubTime(h.ts) + " \u00B7 ${h.steps} steps" + (if (h.files.isNotEmpty()) " \u00B7 ${h.files}" else ""), fontSize = 11.sp, color = c.sub)
                }
            }
            item { Box(Modifier.padding(start = 16.dp, top = 6.dp)) { HubButton("Clear history", c.danger) { AutomationStore.clearRuns() } } }
        }
    }
}

@Composable
private fun FullPage(content: @Composable ColumnScope.() -> Unit) {
    val c = LocalIos.current
    Column(Modifier.fillMaxSize().background(c.bg).padding(top = 8.dp), content = content)
}

/** 2. Planning page: only real events from the planner appear here. */
@Composable
private fun PlanningPage() {
    val c = LocalIos.current
    val events by AutoSession.events.collectAsState()
    FullPage {
        Text("Planning", Modifier.padding(horizontal = 20.dp), fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Working out which agents, skills and plugins this task needs.", Modifier.padding(horizontal = 20.dp), fontSize = 13.sp, color = c.sub)
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp), color = c.accent, trackColor = c.fill)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            items(events) { e -> Text("\u2713  $e", fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp)) }
        }
        Row(Modifier.padding(16.dp)) { HubButton("Cancel", c.danger) { AutoSession.job?.cancel(); AutoSession.stage.value = AutoStage.IDLE } }
    }
}

@Composable
private fun StepEditor(plan: Plan, st: Step, index: Int) {
    val c = LocalIos.current
    @Suppress("UNUSED_VARIABLE") val rev by AutoSession.rev.collectAsState()
    var agent by remember(st.id) { mutableStateOf(st.agent) }
    var instr by remember(st.id) { mutableStateOf(st.instruction) }
    var menu by remember { mutableStateOf(false) }
    var depMenu by remember { mutableStateOf(false) }
    HubCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}. " + st.kind.name.lowercase(), fontSize = 12.sp, color = c.sub, modifier = Modifier.weight(1f))
            Text("\u2191", Modifier.pressScale { val i = plan.steps.indexOf(st); if (i > 0) { plan.steps.removeAt(i); plan.steps.add(i - 1, st); AutoSession.touch() } }.padding(8.dp), fontSize = 16.sp)
            Text("\u2193", Modifier.pressScale { val i = plan.steps.indexOf(st); if (i < plan.steps.size - 2) { plan.steps.removeAt(i); plan.steps.add(i + 1, st); AutoSession.touch() } }.padding(8.dp), fontSize = 16.sp)
            Text("\u2715", Modifier.pressScale {
                plan.steps.remove(st); plan.steps.forEach { o -> o.dependsOn = o.dependsOn.filter { it != st.id } }; AutoSession.touch()
            }.padding(8.dp), fontSize = 16.sp, color = c.danger)
        }
        HubField(agent, { agent = it; st.agent = it; AutoSession.touch() }, "Agent name / role")
        if (st.kind != StepKind.PLUGIN && st.kind != StepKind.TOOL) HubField(instr, { instr = it; st.instruction = it; AutoSession.touch() }, "Instruction", single = false, minLines = 2)
        when (st.kind) {
            StepKind.SKILL, StepKind.DOC -> {
                Box {
                    HubButton("Skill: " + (st.skills.firstOrNull()?.let { SkillEngine.get(it)?.name } ?: "none")) { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        SkillEngine.all().forEach { sk -> DropdownMenuItem(text = { Text(sk.name) }, onClick = { st.skills = listOf(sk.id) + st.skills.drop(1).filter { it != sk.id }; if (sk.outputFormat.isNotEmpty() && st.kind == StepKind.DOC) st.format = sk.outputFormat; AutoSession.touch(); menu = false }) }
                    }
                }
            }
            StepKind.PLUGIN -> {
                Box {
                    HubButton("Plugin: ${st.plugin}.${st.action}") { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        PluginHub.all().filter { PluginHub.isReady(it.id) }.forEach { p -> p.actions.forEach { a ->
                            DropdownMenuItem(text = { Text("${p.name}: ${a.id}") }, onClick = { st.plugin = p.id; st.action = a.id; st.title = "${p.name}: ${a.id}"; AutoSession.touch(); menu = false })
                        } }
                    }
                }
            }
            StepKind.TOOL -> {
                Box {
                    HubButton("Tool: ${st.tool}") { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        listOf("search_web", "http_get", "read_file", "list_files", "get_time").forEach { t -> DropdownMenuItem(text = { Text(t) }, onClick = { st.tool = t; AutoSession.touch(); menu = false }) }
                    }
                }
            }
            else -> {}
        }
        val others = plan.steps.filter { it.id != st.id && it.kind != StepKind.VERIFY }
        if (others.isNotEmpty()) Box {
            Text("After: " + (if (st.dependsOn.isEmpty()) "runs first (can run in parallel)" else st.dependsOn.joinToString()), fontSize = 12.sp, color = c.sub, modifier = Modifier.pressScale { depMenu = true }.padding(vertical = 6.dp))
            DropdownMenu(depMenu, { depMenu = false }) {
                others.forEach { o -> DropdownMenuItem(text = { Text((if (o.id in st.dependsOn) "\u2713 " else "") + o.id + " " + o.title) }, onClick = {
                    st.dependsOn = if (o.id in st.dependsOn) st.dependsOn - o.id else st.dependsOn + o.id; AutoSession.touch() }) }
            }
        }
    }
}

/** 3. Implementation plan: agents, skills, plugins, tools, warnings. Everything is editable. */
@Composable
private fun PlanReviewPage(vm: AstraViewModel, onFixPlugins: () -> Unit) {
    val c = LocalIos.current
    val ctx = LocalContext.current
    val use24h = remember { android.text.format.DateFormat.is24HourFormat(ctx) }
    val plan by AutoSession.draft.collectAsState()
    @Suppress("UNUSED_VARIABLE") val rev by AutoSession.rev.collectAsState()
    val refining by AutoSession.refining.collectAsState()
    val message by AutoSession.message.collectAsState()
    val events by AutoSession.events.collectAsState()
    val name by AutoSession.name.collectAsState()
    val saveTick by AutomationStore.changed.collectAsState()
    val card = remember(saveTick) { AutoSession.cardId.value?.let { AutomationStore.get(it) } }
    var sheet by remember { mutableStateOf(false) }
    val p = plan ?: return
    var addMenu by remember { mutableStateOf(false) }
    if (sheet && card != null) ScheduleSheet(card.name, card.schedule, onDismiss = { sheet = false }) { s ->
        AutoSession.cardId.value?.let { AutomationStore.setSchedule(it, s); AutoSession.name.value = AutomationStore.get(it)?.name ?: AutoSession.name.value }
        sheet = false
    }
    FullPage {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ AutoSession.job?.cancel(); AutoSession.stage.value = AutoStage.IDLE }) { Icon(Icons.Rounded.ArrowBack, "Back", tint = c.text) }
            Text("Implementation plan", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                HubCard {
                    HubField(name, { AutoSession.name.value = it }, "Automation name")
                    Text(p.goal, fontSize = 15.sp)
                    Text("Skills: " + (p.skills.joinToString { SkillEngine.get(it)?.name ?: it }.ifEmpty { "none" }), fontSize = 12.sp, color = c.sub, modifier = Modifier.padding(top = 6.dp))
                    Text("Plugins: " + (p.plugins.joinToString { PluginHub.plugin(it)?.name ?: it }.ifEmpty { "none" }), fontSize = 12.sp, color = c.sub)
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(card?.schedule?.let { Schedules.chip(it, java.time.ZonedDateTime.now(), use24h) } ?: "No schedule yet",
                            Modifier.weight(1f), fontSize = 12.sp, color = c.sub)
                        HubButton(if (card?.schedule == null) "Schedule" else "Edit schedule") { sheet = true }
                    }
                    if (refining) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c.accent); Spacer(Modifier.width(8.dp))
                        Text("The model is refining this plan (up to 8 s)", fontSize = 12.sp, color = c.sub)
                    }
                }
            }
            // warnings with a Fix button
            if (p.steps.any { it.kind == StepKind.DEVICE } && !AutoPilot.accessibilityOn()) item {
                HubCard { Text("Accessibility is off", fontWeight = FontWeight.SemiBold); Text("Phone steps need the ZENIQ accessibility service.", fontSize = 12.sp, color = c.sub)
                    Box(Modifier.padding(top = 6.dp)) { HubButton("Fix: open Accessibility") { AutoFlow.openAccessibility(ctx) } } }
            }
            items(p.notes) { n -> HubCard { Text(n, fontSize = 13.sp); Box(Modifier.padding(top = 6.dp)) { HubButton("Fix: open Plugins") { AutoSession.stage.value = AutoStage.IDLE; onFixPlugins() } } } }
            val work = p.steps.filter { it.kind != StepKind.VERIFY }
            items(work, key = { it.id }) { st -> StepEditor(p, st, work.indexOf(st)) }
            item {
                Box(Modifier.padding(start = 16.dp, top = 6.dp)) {
                    HubButton("+ Add step") { addMenu = true }
                    DropdownMenu(addMenu, { addMenu = false }) {
                        fun add(kind: StepKind, agent: String, instr: String, skill: String = "") {
                            var n = 1; while (p.steps.any { it.id == "s$n" }) n++
                            val st = Step("s$n", agent, kind, agent, emptyList(), if (skill.isEmpty()) emptyList() else listOf(skill), instruction = instr)
                            val at = p.steps.indexOfFirst { it.kind == StepKind.VERIFY }.let { if (it < 0) p.steps.size else it }
                            p.steps.add(at, st); p.steps.firstOrNull { it.kind == StepKind.VERIFY }?.let { v -> v.dependsOn = v.dependsOn + st.id }
                            AutoSession.touch(); addMenu = false
                        }
                        DropdownMenuItem(text = { Text("Phone step") }, onClick = { add(StepKind.DEVICE, "Phone Agent", "Open the app and do what the task says") })
                        DropdownMenuItem(text = { Text("Browser step") }, onClick = { add(StepKind.BROWSER, "Browser Agent", "Open the website and do what the task says") })
                        DropdownMenuItem(text = { Text("Writing step") }, onClick = { add(StepKind.SKILL, "Content Agent", "Write the answer from the earlier results", "writing") })
                    }
                }
            }
            if (events.isNotEmpty()) item {
                HubCard { Text("Planner log", fontSize = 12.sp, color = c.sub); events.forEach { Text("\u2022 $it", fontSize = 11.sp, color = c.sub) } }
            }
        }
        if (message.isNotEmpty()) Text(message, Modifier.padding(horizontal = 20.dp), fontSize = 13.sp, color = c.danger)
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HubButton("Cancel", c.danger) { AutoSession.job?.cancel(); AutoSession.stage.value = AutoStage.IDLE }
            HubButton("Approve", modifier = Modifier.weight(1f)) { AutoFlow.approve(vm, ctx) }
        }
    }
}

@Composable
private fun RunView(vm: AstraViewModel, r: AutoRun) {
    val c = LocalIos.current
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    @Suppress("UNUSED_VARIABLE") val tick by r.tick.collectAsState()
    var showJson by remember { mutableStateOf(false) }
    r.plan.steps.forEach { st ->
        var open by remember(st.id) { mutableStateOf(false) }
        HubCard(Modifier.pressScale { open = !open }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(statusIcon(st.status), fontSize = 16.sp, color = statusColor(st.status, c)); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(st.title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(st.agent + " \u00B7 " + st.kind.name.lowercase() + (if (st.dependsOn.isNotEmpty()) " \u00B7 after " + st.dependsOn.joinToString() else ""), fontSize = 12.sp, color = c.sub)
                }
                HubBadge(st.status + (if (st.retries > 0) " \u00D7${st.retries}" else ""), statusColor(st.status, c))
            }
            if (st.error.isNotEmpty() && st.status != "completed") Text(st.error.take(300), fontSize = 12.sp, color = c.danger, modifier = Modifier.padding(top = 4.dp))
            if (open && st.output.isNotEmpty()) Text(st.output.take(1500), fontSize = 12.sp, color = c.sub, modifier = Modifier.padding(top = 6.dp))
        }
    }
    if (r.recoveries.isNotEmpty()) HubCard { Text("Recovery", fontSize = 12.sp, color = c.sub); r.recoveries.forEach { Text("\u21BB $it", fontSize = 12.sp) } }
    if (r.status != "running" && r.status != "ready") HubCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Result", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            HubBadge(r.status.replace('_', ' ') + (if (r.test) " (test)" else ""), if (r.status == "failed") c.danger else c.text)
        }
        if (r.verification.isNotEmpty()) Text("Verification: ${r.verification}", fontSize = 12.sp, color = c.sub)
        Spacer(Modifier.height(6.dp)); Text(r.finalText.take(3500), fontSize = 14.sp)
        r.files.forEach { f ->
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(f.name, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                HubButton("Open") { FileStorage.open(ctx, File(f.path), f.mime) }
                HubButton("Share") { FileStorage.share(ctx, File(f.path), f.mime) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            HubButton("Copy") { clip.setText(AnnotatedString(r.finalText)) }
            HubButton(if (showJson) "Hide report" else "Run report") { showJson = !showJson }
        }
        if (showJson && r.finalJson.isNotEmpty()) Text(r.finalJson, fontSize = 11.sp, color = c.sub, modifier = Modifier.padding(top = 8.dp))
    }
}

/** 4/5. Build: test run with live, real step status; then the result. */
@Composable
private fun BuildPage(vm: AstraViewModel) {
    val c = LocalIos.current
    val ctx = LocalContext.current
    val run by AutoSession.run.collectAsState()
    val message by AutoSession.message.collectAsState()
    val r = run
    @Suppress("UNUSED_VARIABLE") val tick = r?.tick?.collectAsState()?.value
    FullPage {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ if (r?.status != "running") AutoSession.stage.value = AutoStage.IDLE }) { Icon(Icons.Rounded.ArrowBack, "Back", tint = c.text) }
            Text(if (r?.test == true) "Test run" else "Running", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        if (r?.status == "running") LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), color = c.accent, trackColor = c.fill)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (r != null) item { RunView(vm, r) }
        }
        if (message.isNotEmpty()) Text(message, Modifier.padding(horizontal = 20.dp), fontSize = 13.sp, color = c.danger)
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (r?.status == "running") HubButton("Stop", c.danger) { AutoSession.job?.cancel() }
            else {
                HubButton("Edit plan") { AutoSession.stage.value = AutoStage.REVIEW }
                if (r?.test == true) HubButton("Run for real", modifier = Modifier.weight(1f)) { AutoFlow.startRun(vm, ctx, false) }
                else HubButton("Done", modifier = Modifier.weight(1f)) { AutoSession.stage.value = AutoStage.IDLE }
            }
        }
    }
}

/** A saved card: Run, Test run, Edit plan, Schedule, Rename, Duplicate, Share, Delete. */
@Composable
private fun CardPage(vm: AstraViewModel) {
    val c = LocalIos.current
    val ctx = LocalContext.current
    val id = AutoSession.cardId.collectAsState().value ?: return
    val tick by AutomationStore.changed.collectAsState()
    val card = remember(tick, id) { AutomationStore.get(id) }
    val plan by AutoSession.draft.collectAsState()
    val run by AutoSession.run.collectAsState()
    val message by AutoSession.message.collectAsState()
    val use24h = remember { android.text.format.DateFormat.is24HourFormat(ctx) }
    val r = run
    @Suppress("UNUSED_VARIABLE") val rt = r?.tick?.collectAsState()?.value

    var menu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var newName by remember(card?.name) { mutableStateOf(card?.name.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (sheet && card != null) ScheduleSheet(card.name, card.schedule, onDismiss = { sheet = false }) { s ->
        AutomationStore.setSchedule(id, s); sheet = false
    }
    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false }, containerColor = c.card,
            title = { Text("Rename automation") },
            text = { HubField(newName, { newName = it }, "Name") },
            confirmButton = { TextButton({ AutoSession.name.value = AutomationStore.rename(id, newName) ?: newName; renaming = false }) { Text("Save") } },
            dismissButton = { TextButton({ renaming = false }) { Text("Cancel") } }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false }, containerColor = c.card,
            title = { Text("Delete this automation?") },
            text = { Text("\"${card?.name ?: "This automation"}\" and its schedule will be removed. This cannot be undone.") },
            confirmButton = { TextButton({ confirmDelete = false; AutomationStore.delete(id); AutoSession.stage.value = AutoStage.IDLE }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } }
        )
    }

    FullPage {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ if (r?.status != "running") AutoSession.stage.value = AutoStage.IDLE }) { Icon(Icons.Rounded.ArrowBack, "Back", tint = c.text) }
            Text(card?.name ?: "Automation", Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Box {
                Text("\u22EF", Modifier.pressScale { menu = true }.padding(horizontal = 14.dp, vertical = 6.dp), fontSize = 24.sp)
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; newName = card?.name.orEmpty(); renaming = true })
                    DropdownMenuItem(text = { Text("Edit plan") }, onClick = { menu = false; AutoSession.run.value = null; AutoSession.stage.value = AutoStage.REVIEW })
                    DropdownMenuItem(text = { Text("Edit schedule") }, onClick = { menu = false; sheet = true })
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; AutomationStore.duplicate(id)?.let { AutoFlow.openCard(it.id) } })
                    DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; shareAutomation(ctx, id) })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                HubCard {
                    Text(card?.request.orEmpty(), fontSize = 14.sp)
                    val sched = card?.schedule
                    Text(
                        sched?.let { Schedules.chip(it, java.time.ZonedDateTime.now(), use24h) } ?: "No schedule",
                        fontSize = 12.sp, color = c.sub, modifier = Modifier.padding(top = 6.dp)
                    )
                    if (card != null) Text(
                        (if (card.lastStatus.isNotEmpty()) "Last: ${card.lastStatus.replace('_', ' ')} \u00B7 " + hubTime(card.lastRun) else "Not run yet") +
                            (if (sched?.runMode == com.astra.agent.hub.RunMode.NOTIFY) " \u00B7 notify me" else ""),
                        fontSize = 12.sp, color = c.sub, modifier = Modifier.padding(top = 2.dp)
                    )
                    plan?.let { p -> if (p.steps.any { it.kind == StepKind.DEVICE || it.kind == StepKind.BROWSER } && !AutoPilot.accessibilityOn()) {
                        Text("Accessibility is off: phone and browser steps cannot run.", fontSize = 12.sp, color = c.danger, modifier = Modifier.padding(top = 6.dp))
                        Box(Modifier.padding(top = 4.dp)) { HubButton("Open Accessibility settings") { AutoFlow.openAccessibility(ctx) } } } }
                }
            }
            if (card?.schedule != null) item { AlarmHints() }
            if (r == null) plan?.let { p -> items(p.steps.filter { it.kind != StepKind.VERIFY }) { st ->
                HubCard { Text(st.title, fontSize = 15.sp, fontWeight = FontWeight.Medium); Text(st.agent + " \u00B7 " + st.kind.name.lowercase(), fontSize = 12.sp, color = c.sub) }
            } } else item { RunView(vm, r) }
        }
        if (message.isNotEmpty()) Text(message, Modifier.padding(horizontal = 20.dp), fontSize = 13.sp, color = c.danger)
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r?.status == "running") HubButton("Stop", c.danger) { AutoSession.job?.cancel() } else {
                    HubButton("Run", modifier = Modifier.weight(1f)) { AutoFlow.startRun(vm, ctx, false) }
                    HubButton("Test run") { AutoFlow.startRun(vm, ctx, true) }
                    HubButton("Edit plan") { AutoSession.run.value = null; AutoSession.stage.value = AutoStage.REVIEW }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                HubButton(if (card?.schedule == null) "Schedule" else "Edit schedule") { sheet = true }
                if (card?.schedule != null) HubButton(if (card.schedule.enabled) "Pause" else "Resume") {
                    AutomationStore.setEnabled(id, !card.schedule.enabled)
                }
                HubButton("Duplicate") { AutomationStore.duplicate(id)?.let { AutoFlow.openCard(it.id) } }
                Spacer(Modifier.weight(1f))
                HubButton("Delete", c.danger) { confirmDelete = true }
            }
        }
    }
}

private fun shareAutomation(ctx: android.content.Context, id: String) {
    val txt = AutomationStore.shareText(id) ?: return
    try {
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, txt), "Share automation"))
    } catch (_: Exception) {}
}
