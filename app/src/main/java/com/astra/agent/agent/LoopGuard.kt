package com.astra.agent.agent

/**
 * Detects an agent that keeps repeating the same action. 3 identical rounds in a row -> change strategy; 5 -> stop with a reason.
 * Pure Kotlin so it can be unit tested.
 */
class LoopGuard(private val warnAt: Int = 3, private val stopAt: Int = 5) {
    enum class Verdict { OK, CHANGE, STOP }
    private var last = ""
    private var count = 0

    /** [signature] = the tool names and arguments of one round (and the screen text, when known). */
    fun record(signature: String): Verdict {
        if (signature == last) count++ else { last = signature; count = 1 }
        return when { count >= stopAt -> Verdict.STOP; count >= warnAt -> Verdict.CHANGE; else -> Verdict.OK }
    }

    fun reset() { last = ""; count = 0 }
    val repeats get() = count

    companion object {
        const val DEFAULT_CEILING = 150
        /** Old installs saved the former limit of 12; that value is moved to the new ceiling once. */
        fun migrateSteps(saved: Int, alreadyMigrated: Boolean): Int = if (!alreadyMigrated && saved <= 12) DEFAULT_CEILING else saved.coerceIn(1, 500)

        /**
         * Shortens old tool results (oldest first) instead of dropping messages, so the original task stays in context.
         * Returns the number of characters saved.
         */
        fun squeezeToolResults(contents: MutableList<String>, isToolResult: (String) -> Boolean, keepLast: Int = 2, maxChars: Int = 160): Int {
            var saved = 0
            val idx = contents.indices.filter { isToolResult(contents[it]) }
            for (i in idx.dropLast(keepLast)) {
                val c = contents[i]
                if (c.length > maxChars + 40) { contents[i] = c.take(maxChars) + " ...[earlier result shortened]"; saved += c.length - contents[i].length }
            }
            return saved
        }
    }
}
