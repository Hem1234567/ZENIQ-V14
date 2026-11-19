package com.astra.agent.llm

import com.astra.agent.llm.PromptBudget.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whole-turn trimming: never removes the current task, never leaves a broken turn. Pure JVM. */
class PromptBudgetTest {
    private fun user(s: String) = Msg("user", s)
    private fun asst(s: String) = Msg("assistant", s)

    @Test fun underBudgetIsUntouched() {
        val h = mutableListOf(user("hi"), asst("hello"))
        assertEquals(0, PromptBudget.trim(h, 1000))
        assertEquals(listOf("hi", "hello"), h.map { it.content })
    }

    @Test fun dropsOldestWholeTurnFirst() {
        val h = mutableListOf(
            user("a".repeat(100)), asst("b".repeat(100)),
            user("c".repeat(100)), asst("d".repeat(100)),
            user("current task")
        )
        val removed = PromptBudget.trim(h, 260)
        assertEquals(2, removed)                              // whole first turn: 200 chars
        assertEquals("current task", h.last().content)
        assertTrue(PromptBudget.size(h) <= 260)
    }

    @Test fun keepsTheCurrentTaskEvenWhenItIsTooBig() {
        val h = mutableListOf(user("x".repeat(500)))
        assertEquals(0, PromptBudget.trim(h, 100))
        assertEquals(1, h.size)
    }

    @Test fun neverCutsATurnInHalf() {
        val h = mutableListOf(
            user("question one"), asst("answer one"),
            user("question two"), asst("answer two"),
            user("question three")
        )
        PromptBudget.trim(h, 30)
        assertEquals("question three", h.first().content)     // starts exactly on the last turn
        assertEquals(1, h.size)
    }

    @Test fun toolResultsStayWithTheirTurn() {
        val tool = Msg("user", "<tool_response>{\"ok\":true}</tool_response>")
        val h = mutableListOf(
            user("open youtube"), asst("calling open_app"), tool, asst("opened"),
            user("thanks"), asst("welcome"),
            user("now play music")
        )
        PromptBudget.trim(h, 60)
        // The first turn (including its tool result) goes as a block; the rest is kept intact.
        assertEquals("now play music", h.last().content)
        assertTrue(h.none { it.content.startsWith("<tool_response>") })
    }

    @Test fun removesTurnsUntilItFits() {
        val h = mutableListOf(
            user("a".repeat(50)), asst("b".repeat(50)),
            user("c".repeat(50)), asst("d".repeat(50)),
            user("e".repeat(50)), asst("f".repeat(50)),
            user("task")
        )
        PromptBudget.trim(h, 170)
        assertTrue(PromptBudget.size(h) <= 170)
        assertEquals("task", h.last().content)
    }

    @Test fun sizeCountsCharacters() {
        assertEquals(5, PromptBudget.size(listOf(Msg("user", "hello"))))
    }
}
