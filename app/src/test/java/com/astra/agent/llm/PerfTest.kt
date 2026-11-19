package com.astra.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Timing journal: newest lookup, 40-sample cap and the human label. Pure JVM. */
class PerfTest {
    private fun sample(id: String, tps: Double = 9.4, first: Long = 1200, total: Long = 4600, tokens: Int = 42) =
        PerfSample(id, "local", "Qwen2.5-1.5B", 900, first, total, tokens, tps)

    @Before fun clean() = Perf.clear()

    @Test fun recordsAndLooksUpById() {
        Perf.record(sample("a"))
        val s = Perf.get("a")
        assertNotNull(s)
        assertEquals(42, s!!.tokens)
        assertNull(Perf.get("missing"))
    }

    @Test fun lastIsTheNewestSample() {
        Perf.record(sample("a"))
        Perf.record(sample("b", tps = 12.0))
        assertEquals("b", Perf.last()!!.id)
        assertEquals(2, Perf.history().size)
    }

    @Test fun keepsOnlyTheLastForty() {
        for (i in 1..45) Perf.record(sample("s$i"))
        assertEquals(40, Perf.history().size)
        assertNull(Perf.get("s1"))          // evicted
        assertNotNull(Perf.get("s45"))
        assertEquals("s45", Perf.last()!!.id)
    }

    @Test fun labelCarriesMeasuredNumbersOnly() {
        val label = Perf.label(sample("a", tps = 9.4, first = 1200, total = 4600, tokens = 42))
        assertTrue(label.contains("local Qwen2.5-1.5B"))
        assertTrue(label.contains("first 1.2s"))
        assertTrue(label.contains("9.4 tok/s"))
        assertTrue(label.contains("42 tok"))
        assertTrue(label.contains("total 4.6s"))
    }

    @Test fun labelSkipsAZeroFirstTokenTime() {
        val label = Perf.label(sample("a", first = 0, tps = 0.0, tokens = 0))
        assertTrue(!label.contains("first"))
        assertTrue(!label.contains("tok/s"))
        assertTrue(label.contains("total"))
    }

    @Test fun reportIsEitherEmptyNoticeOrNumberedLines() {
        assertEquals("No answers measured yet.", Perf.report())
        Perf.record(sample("a"))
        Perf.record(sample("b"))
        val lines = Perf.report().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("1. "))
        assertTrue(lines[1].startsWith("2. "))
    }

    @Test fun clearEmptiesEverything() {
        Perf.record(sample("a"))
        Perf.clear()
        assertEquals(0, Perf.history().size)
        assertNull(Perf.get("a"))
        assertNull(Perf.last())
    }
}
