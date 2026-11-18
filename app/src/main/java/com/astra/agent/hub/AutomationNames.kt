package com.astra.agent.hub

import java.util.Locale

/**
 * Names for automation cards. Every card has a real name: 1..40 characters, unique, and never empty.
 */
object AutomationNames {
    const val MAX = 40
    private const val MIN = 1

    private val stop = Regex("^(?:please|kindly|can you|could you|would you|i want to|i need to)\\s+", RegexOption.IGNORE_CASE)

    fun clean(raw: String): String = raw.replace(Regex("\\s+"), " ").trim().take(MAX)

    /** A short, human name derived from the request. Heuristic only: instant and works offline. */
    fun suggest(request: String): String {
        val r = stop.replace(request.trim(), "").trim()
        if (r.isEmpty()) return "Automation"
        val low = r.lowercase(Locale.ROOT)
        val daily = Regex("\\b(every\\s*day|everyday|daily)\\b").containsMatchIn(low)
        fun named(name: String) = clean(name)
        // Well-known tasks get a name that matches the spec's examples.
        if (low.contains("weather")) return named(if (daily) "Daily weather summary" else "Weather update")
        if (low.contains("news") && daily) return named("Daily news digest")
        if (low.contains("morning briefing") || low.contains("morning routine")) return named("Morning briefing")
        if (low.contains("notification") && (low.contains("digest") || low.contains("summar"))) return named("Notification digest")
        if (low.contains("meeting") && (low.contains("remind") || low.contains("summar"))) return named("Meeting reminder summary")
        if (low.contains("commute")) return named("Weather and commute")
        if (low.contains("expense")) return named("Expense note from a message")
        if (low.contains("journal")) return named("Daily journal prompt")
        if (low.contains("weekly review") || (low.contains("review") && low.contains("weekly"))) return named("Weekly review")
        if (low.contains("quiz")) return named("Study quiz from a document")
        if (low.contains("battery") && low.contains("storage")) return named("Battery and storage check")
        if (low.contains("birthday")) return named("Birthday reminders")
        if (low.contains("scan") && low.contains("document")) return named("Scan and file a document")

        val verb = Regex("^(open|launch|start|play|pause|stop|search|find|look up|download|install|send|create|make|generate|build|write|summarize|summarise|check|read|get|call|text|email|remind me to|remind|book|buy|order|upload|share|clean|back up|backup)\\b", RegexOption.IGNORE_CASE)
                .find(r)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        val rest = if (verb != null) r.substring(verb.length).trim().trimStart(':', '-', ',').trim() else r
        val titleVerb = when (verb) {
            "launch" -> "Open"
            "summarise" -> "Summarize"
            "backup" -> "Back up"
            "remind me to", "remind" -> "Reminder:"
            null -> ""
            else -> verb.replaceFirstChar { it.titlecase() }
        }
        val body = rest.ifEmpty { r }.take(MAX)
        val name = if (titleVerb.isEmpty()) body else "$titleVerb $body".replace(" :", ":")
        return clean(name.ifBlank { r })
    }

    /** Appends " 2", " 3", ... until the name is unique (case-insensitive). */
    fun unique(base: String, taken: Collection<String>): String {
        val wanted = clean(base).ifBlank { "Automation" }
        val used = taken.map { it.trim().lowercase(Locale.ROOT) }.toSet()
        if (wanted.lowercase(Locale.ROOT) !in used) return wanted
        var n = 2
        while (true) {
            val suffix = " $n"
            val candidate = wanted.take(MAX - suffix.length).trimEnd() + suffix
            if (candidate.lowercase(Locale.ROOT) !in used) return candidate
            n++
        }
    }

    /** A valid name for saving/renaming: keeps [raw] when possible, otherwise restores the suggestion. */
    fun resolve(raw: String, request: String, taken: Collection<String>): String {
        val c = clean(raw)
        val base = if (c.length >= MIN) c else suggest(request)
        return unique(base, taken)
    }
}
