package com.astra.agent.agent

import org.json.JSONObject

/**
 * Repairs the free-text tool calls small models produce: extra prose around the object, a missing closing
 * brace, arguments as a string, or a name that is close to a real tool ("websearch" -> "web_search").
 * Pure JSON work, so it is unit tested.
 */
object ToolJson {
    /** Names removed in the F19 merge; saved automations and older models still use the old spelling. */
    private val ALIASES = mapOf(
        "search_web" to "web_search",
        "websearch" to "web_search",
        "google_search" to "web_search",
        "search" to "web_search",
        "youtube" to "youtube_search",
        "openapp" to "open_app",
        "open_url" to "open_url"
    )

    /** A repaired `{"name": ..., "arguments": {...}}` string, or null when nothing usable is left. */
    fun repair(raw: String, known: Set<String>): String? {
        val obj = balance(raw) ?: return null
        return try {
            val o = JSONObject(obj)
            val fn = o.optJSONObject("function")
            val rawName = fn?.optString("name").orEmpty().ifBlank {
                o.optString("name").ifBlank { o.optString("tool").ifBlank { o.optString("function_name") } }
            }
            val name = nearest(rawName, known) ?: return null
            val rawArgs: Any = fn?.opt("arguments") ?: o.opt("arguments") ?: o.opt("parameters") ?: o.opt("args") ?: JSONObject()
            val args = when (rawArgs) {
                is JSONObject -> rawArgs
                is String -> try { JSONObject(rawArgs) } catch (_: Exception) { JSONObject() }
                else -> JSONObject()
            }
            JSONObject().put("name", name).put("arguments", args).toString()
        } catch (_: Exception) { null }
    }

    /** The first balanced {...} block in [text], ignoring braces inside strings. */
    fun balance(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        var nestedEnd = -1                            // where the last complete inner object ended
        for (i in start until text.length) {
            val ch = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                    if (depth == 1) nestedEnd = i + 1
                }
            }
        }
        if (depth <= 0) return null
        // A complete inner object followed by prose: drop the prose and close the outer object there.
        if (nestedEnd > start) return text.substring(start, nestedEnd) + "}"
        // Unclosed object: close the open string and add the missing braces (small models often stop one token early).
        var tail = text.substring(start).trimEnd()
        if (inString) {
            tail += "\""
        } else when {
            tail.endsWith(":") -> tail += "null"
            tail.endsWith(",") -> tail = tail.dropLast(1)
        }
        return tail + "}".repeat(depth)
    }

    /** Exact name, an alias, a containment match, or a name one or two typos away. */
    fun nearest(name: String, known: Set<String>): String? {
        val n = name.trim().lowercase()
        if (n.isEmpty() || known.isEmpty()) return null
        if (n in known) return n
        ALIASES[n]?.let { if (it in known) return it }
        known.firstOrNull { it.lowercase() == n }?.let { return it }
        known.firstOrNull { n.length >= 4 && (it.lowercase().contains(n) || n.contains(it.lowercase())) }?.let { return it }
        val max = when (n.length) { in 0..4 -> 1; in 5..9 -> 2; else -> 3 }
        return known.minByOrNull { distance(n, it.lowercase()) }?.takeIf { distance(n, it.lowercase()) <= max }
    }

    /** Levenshtein distance, iterative with one row. */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
