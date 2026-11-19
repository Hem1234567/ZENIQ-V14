package com.astra.agent.llm

/**
 * Pure context trimming. The system prompt is not part of the history, so it is never touched.
 *
 * Removing one single oldest message shifts every later token and throws away the model's KV-cache prefix.
 * This drops a whole old turn at a time and never removes the message that carries the current task.
 */
object PromptBudget {
    data class Msg(val role: String, val content: String)

    private fun isToolResult(text: String) = text.startsWith("<tool_response>")

    fun size(history: List<Msg>): Int = history.sumOf { it.content.length }

    /**
     * Removes the oldest complete turn(s) until [budgetChars] fits.
     * Returns the number of messages removed.
     */
    fun trim(history: MutableList<Msg>, budgetChars: Int): Int =
        trim(history, budgetChars, { it.role }, { it.content })

    /** Same trim over any message type (the agent's Msg also carries images). */
    fun <T> trim(history: MutableList<T>, budgetChars: Int, role: (T) -> String, text: (T) -> String): Int {
        var removed = 0
        while (history.sumOf { text(it).length } > budgetChars) {
            val task = history.indexOfLast { role(it) == "user" && !isToolResult(text(it)) }
            if (task <= 0) break                       // only the current task is left: never drop it
            val nextUser = (1 until task).firstOrNull { role(history[it]) == "user" } ?: task
            if (nextUser <= 0) break
            repeat(nextUser) { history.removeAt(0) }
            removed += nextUser
        }
        return removed
    }
}
