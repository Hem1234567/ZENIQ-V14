package com.astra.agent.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astra.agent.Alarms
import com.astra.agent.hub.RepeatType
import com.astra.agent.hub.RunMode
import com.astra.agent.hub.Schedule
import com.astra.agent.hub.Schedules
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * The schedule editor: quick presets, a big numeric time field, repeat rules, a natural-language field,
 * a live "next run" preview and the run mode. Black and white only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleSheet(
    title: String,
    initial: Schedule?,
    onDismiss: () -> Unit,
    onSave: (Schedule?) -> Unit
) {
    val c = LocalIos.current
    val ctx = LocalContext.current
    val use24h = remember { android.text.format.DateFormat.is24HourFormat(ctx) }
    val now = remember { ZonedDateTime.now() }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var repeat by remember { mutableStateOf(initial?.repeat ?: RepeatType.ONCE) }
    var daysMask by remember { mutableStateOf(initial?.daysMask?.takeIf { it != 0 } ?: Schedules.WEEKDAYS_MASK) }
    var intervalMinutes by remember { mutableStateOf(initial?.intervalMinutes ?: 120) }
    var runMode by remember { mutableStateOf(initial?.runMode ?: RunMode.AUTO) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var hour by remember { mutableStateOf(initial?.hour ?: 8) }
    var minute by remember { mutableStateOf(initial?.minute ?: 0) }
    var digits by remember { mutableStateOf(Schedules.digitsFor(hour, minute, use24h)) }
    var pm by remember { mutableStateOf(hour >= 12) }
    var dateEpochDay by remember {
        mutableStateOf(
            initial?.dateEpochDay?.takeIf { it > 0 } ?: run {
                val d = now.toLocalDate()
                if (!d.atTime(hour, minute).isAfter(now.toLocalDateTime())) d.plusDays(1).toEpochDay() else d.toEpochDay()
            }
        )
    }
    var nlNotice by remember { mutableStateOf("") }

    val unitHours = intervalMinutes >= 60 && intervalMinutes % 60 == 0
    var intervalText by remember { mutableStateOf(if (unitHours) (intervalMinutes / 60).toString() else intervalMinutes.toString()) }
    var useHours by remember { mutableStateOf(unitHours) }

    fun syncTime(h: Int, m: Int) {
        hour = h; minute = m
        digits = Schedules.digitsFor(h, m, use24h)
        pm = h >= 12
    }

    fun applyPreset(s: Schedule) {
        repeat = s.repeat
        daysMask = s.daysMask.takeIf { it != 0 } ?: daysMask
        intervalMinutes = s.intervalMinutes
        intervalText = if (s.intervalMinutes % 60 == 0 && s.intervalMinutes >= 60) (s.intervalMinutes / 60).toString() else s.intervalMinutes.toString()
        useHours = s.intervalMinutes % 60 == 0 && s.intervalMinutes >= 60
        if (s.repeat == RepeatType.ONCE && s.dateEpochDay > 0) dateEpochDay = s.dateEpochDay
        syncTime(s.hour, s.minute)
        nlNotice = "Set to " + Schedules.describe(s, use24h) + "."
    }

    fun current(): Schedule = Schedule(
        repeat = repeat, hour = hour, minute = minute, daysMask = daysMask, intervalMinutes = intervalMinutes,
        dateEpochDay = if (repeat == RepeatType.ONCE) dateEpochDay else 0L,
        anchor = if (repeat == RepeatType.INTERVAL) initial?.anchor?.takeIf { it > 0 } ?: now.toInstant().toEpochMilli() else 0L,
        enabled = enabled, runMode = runMode, lastRun = initial?.lastRun ?: 0L, nextRun = 0L
    )

    val schedule = current()
    val problems = Schedules.problems(schedule, now)
    val dateLabel = runCatching { LocalDate.ofEpochDay(dateEpochDay) }.getOrNull()
        ?.let { d ->
            val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), d)
            when (days) { 0L -> "Today"; 1L -> "Tomorrow"; else -> "${d.dayOfMonth} ${d.month.name.lowercase().replaceFirstChar { ch -> ch.uppercase() }}" }
        } ?: "Today"

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.bg, contentColor = c.text) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 28.dp)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("ZENIQ does it at the times below, even if this screen is closed.",
                fontSize = 12.sp, color = c.sub, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))

            // Quick presets
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("In 30 min") { applyPreset(Schedules.inMinutes(now, 30)) }
                PillButton("In 1 hour") { applyPreset(Schedules.inMinutes(now, 60)) }
                PillButton("Tonight 7 PM") {
                    val at = now.toLocalTime().let { if (it.hour < 19) now.toLocalDate() else now.toLocalDate().plusDays(1) }
                    applyPreset(Schedules.at(now, at, 19, 0))
                }
                PillButton("Tomorrow 8 AM") { applyPreset(Schedules.at(now, now.toLocalDate().plusDays(1), 8, 0)) }
                PillButton("Every day 8 AM") { applyPreset(Schedule(repeat = RepeatType.DAILY, hour = 8, minute = 0)) }
            }

            Label("Repeat")
            FlowRowOf(listOf(
                "Once" to RepeatType.ONCE, "Every day" to RepeatType.DAILY,
                "Weekdays" to RepeatType.WEEKDAYS, "Weekends" to RepeatType.WEEKENDS,
                "Pick days" to RepeatType.CUSTOM, "Every few hours" to RepeatType.INTERVAL
            )) { (label, type) ->
                ChoiceChip(label, repeat == type) {
                    repeat = type
                    if (type == RepeatType.INTERVAL && intervalMinutes < 15) intervalMinutes = 120
                }
            }

            if (repeat == RepeatType.CUSTOM) {
                Label("Days")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, label ->
                        val on = daysMask and (1 shl i) != 0
                        Box(Modifier.weight(1f).aspectRatio(1f).clip(CircleShape)
                            .background(if (on) c.accent else c.fill)
                            .pressScale { daysMask = daysMask xor (1 shl i) }, contentAlignment = Alignment.Center) {
                            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) c.onAccent else c.text)
                        }
                    }
                }
            }

            if (repeat == RepeatType.INTERVAL) {
                Label("Repeat every")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(c.fill).padding(horizontal = 14.dp, vertical = 12.dp)) {
                        if (intervalText.isEmpty()) Text("120", color = c.sub, fontSize = 18.sp)
                        BasicTextField(intervalText, { v ->
                            intervalText = v.filter { it.isDigit() }.take(4)
                            val n = intervalText.toIntOrNull() ?: 0
                            intervalMinutes = (if (useHours) n * 60 else n).coerceIn(1, 24 * 60)
                        }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = TextStyle(color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                            cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
                    }
                    ChoiceChip("Minutes", !useHours) {
                        useHours = false
                        intervalText = (if (intervalMinutes >= 15) intervalMinutes else 15).toString()
                    }
                    ChoiceChip("Hours", useHours) {
                        useHours = true
                        intervalText = (intervalMinutes / 60).coerceAtLeast(1).toString()
                        intervalMinutes = (intervalText.toIntOrNull() ?: 1) * 60
                    }
                }
                if (intervalMinutes < 15) Text("The shortest interval is 15 minutes.", fontSize = 12.sp, color = c.danger, modifier = Modifier.padding(top = 4.dp))
            }

            if (repeat != RepeatType.INTERVAL) {
                Label("Time")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(c.fill).padding(horizontal = 16.dp, vertical = 10.dp)) {
                        if (digits.isEmpty()) Text("8", color = c.sub, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        BasicTextField(
                            value = digits,
                            onValueChange = { v ->
                                digits = v.filter { it.isDigit() || it == ':' }.take(5)
                                Schedules.parseDigits(digits, use24h, pm)?.let { hour = it.hour; minute = it.minute }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = TextStyle(color = c.text, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
                            cursorBrush = SolidColor(c.accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (!use24h) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChoiceChip("AM", !pm) {
                            pm = false
                            Schedules.parseDigits(digits, false, false)?.let { hour = it.hour; minute = it.minute }
                        }
                        ChoiceChip("PM", pm) {
                            pm = true
                            Schedules.parseDigits(digits, false, true)?.let { hour = it.hour; minute = it.minute }
                        }
                    }
                }
                Text(if (use24h) "Type the time, for example 830 or 1745." else "Type the time, for example 830, then pick AM or PM.",
                    fontSize = 12.sp, color = c.sub, modifier = Modifier.padding(top = 4.dp))
            }

            if (repeat == RepeatType.ONCE) {
                Label("Date")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("Today", dateEpochDay == now.toLocalDate().toEpochDay()) { dateEpochDay = now.toLocalDate().toEpochDay() }
                    ChoiceChip("Tomorrow", dateEpochDay == now.toLocalDate().plusDays(1).toEpochDay()) { dateEpochDay = now.toLocalDate().plusDays(1).toEpochDay() }
                    ChoiceChip(dateLabel, dateEpochDay != now.toLocalDate().toEpochDay() && dateEpochDay != now.toLocalDate().plusDays(1).toEpochDay()) {
                        val base = runCatching { LocalDate.ofEpochDay(dateEpochDay) }.getOrDefault(now.toLocalDate())
                        DatePickerDialog(ctx, { _, y, m, d ->
                            dateEpochDay = LocalDate.of(y, m + 1, d).toEpochDay()
                        }, base.year, base.monthValue - 1, base.dayOfMonth).show()
                    }
                }
            }

            Label("Type it")
            var nlText by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { HubField(nlText, { nlText = it }, "every weekday at 8:30") }
                PillButton("Apply", enabled = nlText.isNotBlank()) {
                    val parsed = Schedules.parse(nlText, now, use24h, scheduleOnly = true)
                    if (parsed == null) nlNotice = "I could not read a time in that. Try \"every day at 8\" or \"tomorrow at 18:00\"."
                    else applyPreset(parsed.schedule)
                }
            }
            if (nlNotice.isNotEmpty()) Text(nlNotice, fontSize = 12.sp, color = c.sub, modifier = Modifier.padding(top = 4.dp))

            // Live preview
            Box(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.fill).padding(14.dp)) {
                Column {
                    Text(Schedules.chip(schedule, now, use24h), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(Schedules.preview(schedule, now, use24h), fontSize = 12.sp, color = c.sub, modifier = Modifier.padding(top = 2.dp))
                    problems.forEach { p -> Text(p, fontSize = 12.sp, color = c.danger, modifier = Modifier.padding(top = 2.dp)) }
                }
            }

            Label("When it is time")
            HubSeg(listOf("Run automatically", "Just notify me"), if (runMode == RunMode.AUTO) 0 else 1) {
                runMode = if (it == 0) RunMode.AUTO else RunMode.NOTIFY
            }
            Text(
                if (runMode == RunMode.AUTO) "ZENIQ runs the steps at the scheduled time. Phone steps need Accessibility and an unlocked screen."
                else "ZENIQ sends a notification at the scheduled time; tap it to run the steps then.",
                fontSize = 12.sp, color = c.sub, modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp)
            )

            AlarmHints(includeOk = false)

            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Enabled", Modifier.weight(1f), fontSize = 14.sp)
                HubSwitch(enabled, { enabled = it })
            }

            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (initial != null) HubButton("Remove", c.danger) { onSave(null) }
                HubButton(
                    if (initial == null) "Save schedule" else "Save changes",
                    enabled = problems.isEmpty(),
                    modifier = Modifier.weight(1f)
                ) { onSave(current()) }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), Modifier.padding(top = 16.dp, bottom = 6.dp), fontSize = 11.sp,
        color = LocalIos.current.sub, letterSpacing = 0.4.sp)
}

@Composable
private fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalIos.current
    Box(Modifier.clip(CircleShape).background(if (selected) c.accent else c.fill).pressScale(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) c.onAccent else c.text, maxLines = 1)
    }
}

/** Wrapping chip rows without a lazy grid: three chips per line. */
@Composable
private fun <T> FlowRowOf(items: List<T>, content: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { item -> Box(Modifier.weight(1f)) { content(item) } }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * System settings that stop scheduled runs from firing or from showing results. Each row has a direct fix button;
 * nothing is hidden or silently failing.
 */
@Composable
fun AlarmHints(includeOk: Boolean = false) {
    val ctx = LocalContext.current
    val c = LocalIos.current
    val exact = Alarms.canScheduleExact(ctx)
    val notif = Alarms.notificationsBlocked(ctx)
    val battery = Alarms.batteryOptimized(ctx)
    if (exact && !notif && !battery) {
        if (includeOk) HubCard {
            Text("Scheduled runs are healthy", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Exact alarms are allowed and notifications are on.", fontSize = 12.sp, color = c.sub)
        }
        return
    }
    HubCard {
        Text("Scheduled runs may not fire", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (!exact) HintRow("Exact alarms are off", "Android may run automations late. Allow alarms and reminders for ZENIQ.", "Fix") { Alarms.exactAlarmSettings(ctx) }
        if (notif) HintRow("Notifications are off", "You will not see results or reminders.", "Fix") { Alarms.notificationSettings(ctx) }
        if (battery) HintRow("Battery saver may delay runs", "Allow ZENIQ to run in the background.", "Fix") { Alarms.batterySettings(ctx) }
    }
}

@Composable
private fun HintRow(title: String, sub: String, action: String, onClick: () -> Unit) {
    val c = LocalIos.current
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(sub, fontSize = 11.sp, color = c.sub)
        }
        PillButton(action) { onClick() }
    }
}
