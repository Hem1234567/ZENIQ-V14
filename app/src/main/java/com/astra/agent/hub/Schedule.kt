package com.astra.agent.hub

import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Locale

/** How a schedule repeats. */
enum class RepeatType { ONCE, DAILY, WEEKDAYS, WEEKENDS, CUSTOM, INTERVAL }

/** What happens at the scheduled time. */
enum class RunMode { AUTO, NOTIFY }

/**
 * One schedule. It is stored on the automation card, which is the only place schedule data lives.
 * Days in [daysMask] use bit 0 = Monday ... bit 6 = Sunday.
 * [anchor] is the first arm time for interval schedules, [dateEpochDay] the date of a ONCE schedule.
 */
data class Schedule(
    val repeat: RepeatType = RepeatType.ONCE,
    val hour: Int = 8,
    val minute: Int = 0,
    val daysMask: Int = 0,
    val intervalMinutes: Int = 120,
    val dateEpochDay: Long = 0L,
    val anchor: Long = 0L,
    val enabled: Boolean = true,
    val runMode: RunMode = RunMode.AUTO,
    val lastRun: Long = 0L,
    val nextRun: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject()
        .put("repeat", repeat.name).put("hour", hour).put("minute", minute).put("days", daysMask)
        .put("interval", intervalMinutes).put("date", dateEpochDay).put("anchor", anchor)
        .put("enabled", enabled).put("runMode", runMode.name).put("lastRun", lastRun).put("nextRun", nextRun)

    companion object {
        fun fromJson(o: JSONObject?): Schedule? {
            if (o == null) return null
            return try {
                Schedule(
                    repeat = RepeatType.valueOf(o.optString("repeat", RepeatType.ONCE.name)),
                    hour = o.optInt("hour", 8).coerceIn(0, 23),
                    minute = o.optInt("minute", 0).coerceIn(0, 59),
                    daysMask = o.optInt("days", 0),
                    intervalMinutes = o.optInt("interval", 120).coerceAtLeast(15),
                    dateEpochDay = o.optLong("date", 0L),
                    anchor = o.optLong("anchor", 0L),
                    enabled = o.optBoolean("enabled", true),
                    runMode = try { RunMode.valueOf(o.optString("runMode", RunMode.AUTO.name)) } catch (_: Exception) { RunMode.AUTO },
                    lastRun = o.optLong("lastRun", 0L),
                    nextRun = o.optLong("nextRun", 0L)
                )
            } catch (_: Exception) { null }
        }
    }
}

/** A typed time from the big numeric field. [hour] is always 24-hour. */
data class TimeEntry(val hour: Int, val minute: Int)

/**
 * A schedule phrase found in text. [prompt] is the text with the schedule phrase removed; empty when the
 * text only described a schedule (the sheet's natural-language field).
 */
class ParsedSchedule(val schedule: Schedule, val prompt: String, val phrase: String)

/**
 * Schedule maths and parsing. Pure Kotlin (java.time only) so it is covered by JVM unit tests.
 */
object Schedules {
    const val MON = 1
    const val TUE = 2
    const val WED = 4
    const val THU = 8
    const val FRI = 16
    const val SAT = 32
    const val SUN = 64
    const val WEEKDAYS_MASK = MON or TUE or WED or THU or FRI
    const val WEEKENDS_MASK = SAT or SUN

    private val DAY_KEYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    // ── formatting ────────────────────────────────────────────────────────
    fun formatTime(hour: Int, minute: Int, use24h: Boolean): String {
        val h = hour.coerceIn(0, 23); val m = minute.coerceIn(0, 59)
        if (use24h) return "%02d:%02d".format(h, m)
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "%d:%02d %s".format(h12, m, if (h < 12) "AM" else "PM")
    }

    private fun formatShortDate(d: ZonedDateTime, use24h: Boolean): String =
        "${DAY_KEYS[d.dayOfWeek.value - 1]} ${d.dayOfMonth} ${MONTHS[d.monthValue - 1]}, ${formatTime(d.hour, d.minute, use24h)}"

    /** "Today 8:30 AM", "Tomorrow 8:30 AM", "Tue 17 Nov 8:30 AM". */
    fun dayLabel(d: ZonedDateTime, now: ZonedDateTime, use24h: Boolean): String {
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), d.toLocalDate())
        val time = formatTime(d.hour, d.minute, use24h)
        return when (days) {
            0L -> "Today $time"
            1L -> "Tomorrow $time"
            else -> "${DAY_KEYS[d.dayOfWeek.value - 1]} ${d.dayOfMonth} ${MONTHS[d.monthValue - 1]} $time"
        }
    }

    /** "Every weekday, 8:30 AM", "Every 2 hours", "Once, Tomorrow 6:00 PM". */
    fun describe(s: Schedule, use24h: Boolean = false): String {
        val time = formatTime(s.hour, s.minute, use24h)
        return when (s.repeat) {
            RepeatType.ONCE -> "Once, $time"
            RepeatType.DAILY -> "Every day, $time"
            RepeatType.WEEKDAYS -> "Every weekday, $time"
            RepeatType.WEEKENDS -> "Weekends, $time"
            RepeatType.CUSTOM -> dayNames(s.daysMask).joinToString(", ").ifEmpty { "No days" } + ", $time"
            RepeatType.INTERVAL -> intervalLabel(s.intervalMinutes)
        }
    }

    fun intervalLabel(minutes: Int): String {
        val m = minutes.coerceAtLeast(15)
        return when {
            m % 60 == 0 -> "Every ${m / 60} hour" + (if (m == 60) "" else "s")
            else -> "Every $m minutes"
        }
    }

    fun dayNames(mask: Int): List<String> = DAY_KEYS.filterIndexed { i, _ -> mask and (1 shl i) != 0 }

    fun chip(s: Schedule, now: ZonedDateTime = ZonedDateTime.now(), use24h: Boolean = false): String {
        val base = describe(s, use24h)
        if (!s.enabled) return "Paused \u00B7 $base"
        val next = nextRun(s, now) ?: return "$base \u00B7 no next run"
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), next.toLocalDate())
        val short = when {
            days == 0L -> "today " + formatTime(next.hour, next.minute, use24h)
            days == 1L -> "tomorrow " + formatTime(next.hour, next.minute, use24h)
            days >= 2 && days <= 7 -> formatShortDate(next, use24h)
            else -> "${next.dayOfMonth} ${MONTHS[next.monthValue - 1]}, " + formatTime(next.hour, next.minute, use24h)
        }
        return "$base \u00B7 next $short"
    }

    fun relative(ms: Long): String {
        if (ms <= 0) return ""
        val d = Duration.ofMillis(ms)
        val days = d.toDays(); val hours = d.toHours() % 24; val mins = d.toMinutes() % 60
        return when {
            days > 0 -> "$days d $hours h"
            hours > 0 -> "$hours h $mins min"
            mins > 0 -> "$mins min"
            else -> "less than a minute"
        }
    }

    /** "Next run: Tue 17 Nov, 8:30 AM (in 14 h 20 min)" or a clear reason when there is none. */
    fun preview(s: Schedule, now: ZonedDateTime = ZonedDateTime.now(), use24h: Boolean = false): String {
        if (!s.enabled) return "Paused. No alarms are set."
        val next = nextRun(s, now) ?: return if (s.repeat == RepeatType.ONCE) "This time has already passed. Pick a new time." else "No next run."
        val millis = Duration.between(now.toInstant(), next.toInstant()).toMillis()
        return "Next run: ${formatShortDate(next, use24h)} (in ${relative(millis)})"
    }

    fun problems(s: Schedule, now: ZonedDateTime = ZonedDateTime.now()): List<String> {
        val out = mutableListOf<String>()
        if (s.repeat == RepeatType.INTERVAL && s.intervalMinutes < 15) out += "The shortest interval is 15 minutes."
        if (s.repeat == RepeatType.CUSTOM && s.daysMask == 0) out += "Pick at least one day."
        if (s.enabled && nextRun(s, now) == null) out += "This schedule has no next run (its time has passed)."
        return out
    }

    // ── next occurrence ───────────────────────────────────────────────────
    /** The next time this schedule should fire, or null for a `once` schedule whose time has passed. */
    fun nextRun(s: Schedule, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        val zone = now.zone
        fun on(date: LocalDate): ZonedDateTime = date.atTime(LocalTime.of(s.hour.coerceIn(0, 23), s.minute.coerceIn(0, 59))).atZone(zone)
        return when (s.repeat) {
            RepeatType.ONCE -> {
                val date = if (s.dateEpochDay > 0) LocalDate.ofEpochDay(s.dateEpochDay) else now.toLocalDate()
                on(date).takeIf { it.isAfter(now) }
            }
            RepeatType.DAILY -> {
                var d = on(now.toLocalDate())
                if (!d.isAfter(now)) d = on(now.toLocalDate().plusDays(1))
                d
            }
            RepeatType.WEEKDAYS -> {
                var date = now.toLocalDate()
                repeat(8) {
                    if (date.dayOfWeek.value in 1..5) {
                        val t = on(date)
                        if (t.isAfter(now)) return t
                    }
                    date = date.plusDays(1)
                }
                null
            }
            RepeatType.WEEKENDS -> {
                var date = now.toLocalDate()
                repeat(8) {
                    if (date.dayOfWeek.value in 6..7) {
                        val t = on(date)
                        if (t.isAfter(now)) return t
                    }
                    date = date.plusDays(1)
                }
                null
            }
            RepeatType.CUSTOM -> {
                val mask = if (s.daysMask == 0) WEEKDAYS_MASK else s.daysMask
                var date = now.toLocalDate()
                repeat(8) {
                    val bit = 1 shl (date.dayOfWeek.value - 1)
                    if (mask and bit != 0) {
                        val t = on(date)
                        if (t.isAfter(now)) return t
                    }
                    date = date.plusDays(1)
                }
                null
            }
            RepeatType.INTERVAL -> {
                val step = s.intervalMinutes.coerceAtLeast(15) * 60_000L
                val nowMs = now.toInstant().toEpochMilli()
                val base = if (s.anchor > 0) s.anchor else nowMs
                val at = if (base > nowMs) base else base + ((nowMs - base) / step + 1) * step
                Instant.ofEpochMilli(at).atZone(zone)
            }
        }
    }

    // ── digit time entry ──────────────────────────────────────────────────
    /**
     * Parses what the user typed into the big time field: "8" -> 8:00, "830" -> 8:30, "1745" -> 17:45.
     * In 12-hour mode the AM/PM toggle decides 1..12; 4-digit values above 12 are read as 24-hour input.
     */
    fun parseDigits(raw: String, use24h: Boolean, pm: Boolean = false): TimeEntry? {
        val d = raw.filter { it.isDigit() }
        if (d.isEmpty() || d.length > 4) return null
        val h: Int; val m: Int
        when (d.length) {
            1, 2 -> { h = d.toInt(); m = 0 }
            3 -> { h = d[0].digitToInt(); m = d.substring(1).toInt() }
            else -> { h = d.substring(0, 2).toInt(); m = d.substring(2).toInt() }
        }
        if (m !in 0..59 || h !in 0..23) return null
        if (use24h || h > 12) return TimeEntry(h, m)
        if (h == 0) return TimeEntry(0, m)
        return TimeEntry(if (pm) (if (h == 12) 12 else h + 12) else (if (h == 12) 0 else h), m)
    }

    /** The digits the field shows for an existing time ("08:30" in 24-hour mode, "8:30" otherwise). */
    fun digitsFor(hour: Int, minute: Int, use24h: Boolean): String {
        if (use24h) return "%02d%02d".format(hour, minute)
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return if (minute == 0) h12.toString() else "$h12%02d".format(minute)
    }

    // ── natural language ─────────────────────────────────────────────────
    private val everyHours = Regex("\\bevery\\s+(?:(\\d{1,3})\\s*)?(?:hours?|hrs?|h)\\b", RegexOption.IGNORE_CASE)
    private val everyMinutes = Regex("\\bevery\\s+(\\d{1,3})\\s*(?:minutes?|mins?)\\b", RegexOption.IGNORE_CASE)
    private val inTime = Regex("\\bin\\s+(\\d{1,3})\\s*(minutes?|mins?|hours?|hrs?|h)\\b", RegexOption.IGNORE_CASE)
    private val dailyRe = Regex("\\b(every\\s*day|everyday|daily)\\b", RegexOption.IGNORE_CASE)
    private val everyPeriodRe = Regex("\\b(every|each)\\s+(morning|afternoon|evening|night)\\b", RegexOption.IGNORE_CASE)
    private val weekdaysRe = Regex("\\b(every\\s+)?weekdays?\\b|\\b(mon(?:day)?)\\s*(?:-|to|through)\\s*fri(?:day)?\\b", RegexOption.IGNORE_CASE)
    private val weekendsRe = Regex("\\b(every\\s+)?weekends?\\b", RegexOption.IGNORE_CASE)
    private val dayRe = Regex("\\b(mon(?:day)?|tue(?:sday)?|wed(?:nesday)?|thu(?:rsday)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)\\b", RegexOption.IGNORE_CASE)
    private val timeRe = Regex("(?<![\\d:])(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?", RegexOption.IGNORE_CASE)
    private val periodRe = Regex("\\b(morning|afternoon|evening|night|tonight)\\b", RegexOption.IGNORE_CASE)
    private val tomorrowRe = Regex("\\btomorrow\\b", RegexOption.IGNORE_CASE)
    private val todayRe = Regex("\\b(today|tonight)\\b", RegexOption.IGNORE_CASE)

    private data class FoundTime(val hour: Int, val minute: Int, val start: Int, val end: Int)

    private fun findTime(text: String, use24h: Boolean, period: String?, strict: Boolean): FoundTime? {
        val matches = timeRe.findAll(text).filter { it.groupValues[1].isNotBlank() }.toList()
        if (matches.isEmpty()) return null
        val marked = matches.firstOrNull { m ->
            val before = text.substring(maxOf(0, m.range.first - 3), m.range.first).trim().lowercase(Locale.ROOT)
            before == "at" || m.groupValues[3].isNotBlank() || m.groupValues[2].isNotBlank()
        }
        // In chat text a bare number ("summarize 5 articles") is not a time; the sheet's field may still read it.
        val chosen = marked ?: if (strict) return null else matches.first()
        var h = chosen.groupValues[1].toInt()
        val m = chosen.groupValues[2].ifBlank { "0" }.toInt()
        val ap = chosen.groupValues[3].lowercase(Locale.ROOT).replace(".", "")
        if (m !in 0..59) return null
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        if (ap.isEmpty() && h in 1..11 && period in setOf("evening", "night", "tonight", "afternoon")) h += 12
        if (h !in 0..23) return null
        // The matched text may end with spaces before am/pm; use the end of the whole match.
        return FoundTime(h, m, chosen.range.first, chosen.range.last + 1)
    }

    private fun dayMask(text: String): Int? {
        val ms = dayRe.findAll(text).toList()
        if (ms.isEmpty()) return null
        var mask = 0
        for (m in ms) {
            val k = m.groupValues[1].lowercase(Locale.ROOT).take(3)
            val i = when (k) {
                "mon" -> 0; "tue" -> 1; "wed" -> 2; "thu" -> 3; "fri" -> 4; "sat" -> 5; else -> 6
            }
            mask = mask or (1 shl i)
        }
        return mask
    }

    /**
     * Finds a schedule in [text]. Chat messages keep what comes after the schedule phrase as the prompt.
     * With [scheduleOnly] (the sheet's natural-language field) a bare time or day is allowed and the prompt may be empty.
     */
    fun parse(raw: String, now: ZonedDateTime = ZonedDateTime.now(), use24h: Boolean = false, scheduleOnly: Boolean = false): ParsedSchedule? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        fun done(s: Schedule, start: Int, end: Int): ParsedSchedule {
            var a = minOf(start, text.length); var b = minOf(end, text.length)
            while (b < text.length && text[b] in " )].,;:-") b++
            while (a > 0 && text[a - 1] == ' ') a--
            val prompt = (text.substring(0, a) + " " + text.substring(b)).trim()
                .replace(Regex("\\s+"), " ")
                .replace(Regex("^(?:and|then|,)\\s+", RegexOption.IGNORE_CASE), "")
                .trim(',', '.', ';', ':', '-', '\u2013')
            val phrase = text.substring(minOf(a, b), maxOf(a, b)).trim()
            return ParsedSchedule(s.copy(dateEpochDay = if (s.repeat == RepeatType.ONCE && s.dateEpochDay == 0L) now.toLocalDate().toEpochDay() else s.dateEpochDay), prompt, phrase)
        }

        // every N hours / every N minutes / in N minutes or hours
        everyMinutes.find(text)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return null
            if (n < 15) return null
            return done(Schedule(repeat = RepeatType.INTERVAL, intervalMinutes = n, anchor = now.toInstant().toEpochMilli()), m.range.first, m.range.last + 1)
        }
        everyHours.find(text)?.let { m ->
            val n = m.groupValues[1].ifBlank { "1" }.toIntOrNull() ?: return null
            if (n < 1) return null
            return done(Schedule(repeat = RepeatType.INTERVAL, intervalMinutes = n * 60, anchor = now.toInstant().toEpochMilli()), m.range.first, m.range.last + 1)
        }
        inTime.find(text)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return null
            val unit = m.groupValues[2].lowercase(Locale.ROOT)
            val minutes = if (unit.startsWith("h")) n * 60 else n
            if (minutes < 1) return null
            val at = now.plusMinutes(minutes.toLong())
            val s = Schedule(repeat = RepeatType.ONCE, hour = at.hour, minute = at.minute, dateEpochDay = at.toLocalDate().toEpochDay(), anchor = now.toInstant().toEpochMilli())
            return done(s, m.range.first, m.range.last + 1)
        }

        val low = text.lowercase(Locale.ROOT)
        val period = periodRe.find(text)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        val time = findTime(text, use24h, period, strict = !scheduleOnly)
        val tomorrow = tomorrowRe.find(text)
        val today = todayRe.find(text)
        val weekdays = weekdaysRe.find(text)
        val weekends = weekendsRe.find(text)
        val daily = dailyRe.find(text) ?: everyPeriodRe.find(text)
        val mask = dayMask(text)
        val hasDayMarker = mask != null && (scheduleOnly || Regex("\\b(every|on)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text))
        val anyMarker = weekdays != null || weekends != null || daily != null || hasDayMarker || tomorrow != null || today != null

        if (time == null && !anyMarker) return null
        // Chat text like "tomorrow I will travel" is not a schedule: a conversation needs a time or a part of day.
        if (!scheduleOnly && time == null && period == null) return null

        val defaultHour = when (period) {
            "morning" -> 8; "afternoon" -> 14; "evening" -> 19; "night", "tonight" -> 21; else -> 8
        }
        val h = time?.hour ?: defaultHour
        val m = time?.minute ?: 0
        if (h !in 0..23 || m !in 0..59) return null

        val date = when {
            tomorrow != null -> now.toLocalDate().plusDays(1)
            today != null -> now.toLocalDate()
            else -> null
        }
        val end = maxOf(time?.end ?: 0, tomorrow?.range?.last?.plus(1) ?: 0, today?.range?.last?.plus(1) ?: 0,
            periodRe.find(text)?.range?.last?.plus(1) ?: 0, weekdays?.range?.last?.plus(1) ?: 0,
            weekends?.range?.last?.plus(1) ?: 0, daily?.range?.last?.plus(1) ?: 0)

        fun onceStart(): Int = listOfNotNull(
            tomorrow?.range?.first, today?.range?.first, time?.start,
            Regex("\\bat\\b", RegexOption.IGNORE_CASE).find(text)?.range?.first
        ).minOrNull() ?: 0

        // day-of-week (named days) beats the generic daily marker
        if (hasDayMarker) {
            val days = mask ?: 0
            val s = when (days) {
                WEEKDAYS_MASK -> Schedule(repeat = RepeatType.WEEKDAYS, hour = h, minute = m)
                WEEKENDS_MASK -> Schedule(repeat = RepeatType.WEEKENDS, hour = h, minute = m)
                else -> Schedule(repeat = RepeatType.CUSTOM, hour = h, minute = m, daysMask = days)
            }
            val start = listOfNotNull(
                Regex("\\b(every|on)\\b", RegexOption.IGNORE_CASE).find(text)?.range?.first,
                dayRe.find(text)?.range?.first
            ).minOrNull() ?: 0
            return done(s, start, end)
        }
        if (weekdays != null) return done(Schedule(repeat = RepeatType.WEEKDAYS, hour = h, minute = m), weekdays.range.first, end)
        if (weekends != null) return done(Schedule(repeat = RepeatType.WEEKENDS, hour = h, minute = m), weekends.range.first, end)
        if (daily != null) return done(Schedule(repeat = RepeatType.DAILY, hour = h, minute = m), daily.range.first, end)

        if (date != null) {
            var d = date
            // A bare "at 8" that is already past means tomorrow. An explicit "today" is kept so the UI can explain.
            if (tomorrow == null && !d.atTime(h, m).isAfter(now.toLocalDateTime()) && !scheduleOnly) d = d.plusDays(1)
            if (tomorrow == null && !d.atTime(h, m).isAfter(now.toLocalDateTime()) && scheduleOnly && today == null) d = d.plusDays(1)
            return done(Schedule(repeat = RepeatType.ONCE, hour = h, minute = m, dateEpochDay = d.toEpochDay()), onceStart(), end)
        }

        // A marked time with no repeat marker ("open YouTube at 8 pm") runs once at its next occurrence.
        var d = now.toLocalDate()
        if (!d.atTime(h, m).isAfter(now.toLocalDateTime())) d = d.plusDays(1)
        return done(Schedule(repeat = RepeatType.ONCE, hour = h, minute = m, dateEpochDay = d.toEpochDay()), if (scheduleOnly) time?.start ?: 0 else onceStart(), end)
    }

    /** The once schedule used by the quick chips, e.g. "In 30 min". */
    fun inMinutes(now: ZonedDateTime, minutes: Int): Schedule {
        val at = now.plusMinutes(minutes.toLong())
        return Schedule(repeat = RepeatType.ONCE, hour = at.hour, minute = at.minute, dateEpochDay = at.toLocalDate().toEpochDay(), anchor = now.toInstant().toEpochMilli())
    }

    fun at(now: ZonedDateTime, date: LocalDate, hour: Int, minute: Int): Schedule =
        Schedule(repeat = RepeatType.ONCE, hour = hour, minute = minute, dateEpochDay = date.toEpochDay())
}
