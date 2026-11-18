package com.astra.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopGuardTest {
    @Test fun verdictsEscalate() {
        val g = LoopGuard()
        assertEquals(LoopGuard.Verdict.OK, g.record("tap|a"))
        assertEquals(LoopGuard.Verdict.OK, g.record("tap|a"))
        assertEquals(LoopGuard.Verdict.CHANGE, g.record("tap|a"))
        assertEquals(LoopGuard.Verdict.CHANGE, g.record("tap|a"))
        assertEquals(LoopGuard.Verdict.STOP, g.record("tap|a"))
    }
    @Test fun differentCallResets() {
        val g = LoopGuard()
        repeat(2) { g.record("a") }
        assertEquals(LoopGuard.Verdict.OK, g.record("b"))
        assertEquals(1, g.repeats)
    }
    @Test fun oldStepLimitIsMigratedOnce() {
        assertEquals(150, LoopGuard.migrateSteps(12, false))
        assertEquals(40, LoopGuard.migrateSteps(40, false))
        assertEquals(12, LoopGuard.migrateSteps(12, true).coerceAtLeast(12))
    }
    @Test fun squeezeKeepsRecentResultsAndShortensOld() {
        val long = "<tool_response>" + "x".repeat(500)
        val msgs = mutableListOf("task", long, "assistant", long, "assistant", long, long)
        val saved = LoopGuard.squeezeToolResults(msgs, { it.startsWith("<tool_response>") })
        assertTrue(saved > 0)
        assertTrue(msgs[1].length < 300 && msgs[3].length < 300)
        assertEquals(long, msgs[5]); assertEquals(long, msgs[6]); assertEquals("task", msgs[0])
    }
}
