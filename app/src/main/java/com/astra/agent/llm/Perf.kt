package com.astra.agent.llm

import java.util.Locale

/**
 * One measured answer. Times are milliseconds. "tokens" is an estimate (streamed text pieces), not token ids,
 * because the JNI layer does not expose per-token counts yet.
 */
data class PerfSample(
    val id: String,
    val path: String,          // "local" or "cloud"
    val model: String,
    val promptChars: Int,
    val firstTokenMs: Long,
    val totalMs: Long,
    val tokens: Int,
    val tps: Double
)

/** In-memory timing journal (last 40 answers). Nothing is written to disk and nothing leaves the phone. */
object Perf {
    private const val CAP = 40
    private val byId = LinkedHashMap<String, PerfSample>()
    private val journal = ArrayDeque<PerfSample>()

    @Synchronized
    fun record(sample: PerfSample) {
        byId[sample.id] = sample
        while (byId.size > CAP) byId.remove(byId.keys.first())
        journal.addLast(sample)
        while (journal.size > CAP) journal.removeFirst()
    }

    @Synchronized fun get(id: String): PerfSample? = byId[id]

    @Synchronized fun last(): PerfSample? = journal.lastOrNull()

    @Synchronized fun history(): List<PerfSample> = journal.toList()

    @Synchronized
    fun clear() {
        byId.clear()
        journal.clear()
    }

    /** e.g. "local Qwen2.5-1.5B · first 1.2s · 9.4 tok/s · 42 tok · total 4.6s" */
    fun label(s: PerfSample): String {
        val parts = ArrayList<String>(5)
        parts += "${s.path} ${s.model}"
        if (s.firstTokenMs > 0) parts += String.format(Locale.US, "first %.1fs", s.firstTokenMs / 1000.0)
        if (s.tps > 0.0) parts += String.format(Locale.US, "%.1f tok/s", s.tps)
        if (s.tokens > 0) parts += "${s.tokens} tok"
        parts += String.format(Locale.US, "total %.1fs", s.totalMs / 1000.0)
        return parts.joinToString(" \u00B7 ")
    }

    /** Full text report (for the Developer section and for copying into a bug report). */
    @Synchronized
    fun report(): String {
        val h = journal.toList()
        if (h.isEmpty()) return "No answers measured yet."
        return h.mapIndexed { i, s -> "${i + 1}. ${label(s)}" }.joinToString("\n")
    }
}
