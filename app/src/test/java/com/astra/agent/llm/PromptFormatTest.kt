package com.astra.agent.llm

import com.astra.agent.llm.PromptFormat.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chat layouts, the "thinking off" prefill and the stable shared prefix. Pure string work. */
class PromptFormatTest {
    private val system = "You are ZENIQ."

    @Test fun templateSniffing() {
        assertEquals(ChatFmt.CHATML, PromptFormat.detect("<|im_start|>user", "x.gguf"))
        assertEquals(ChatFmt.LLAMA3, PromptFormat.detect("<|start_header_id|>user", "x.gguf"))
        assertEquals(ChatFmt.GEMMA, PromptFormat.detect("<start_of_turn>user", "x.gguf"))
        assertEquals(ChatFmt.PHI, PromptFormat.detect("<|user|>\n<|end|>", "x.gguf"))
        assertEquals(ChatFmt.MISTRAL, PromptFormat.detect("[INST] hi [/INST]", "x.gguf"))
        assertEquals(ChatFmt.DEEPSEEK, PromptFormat.detect("<\uFF5CUser\uFF5C>", "x.gguf"))
        assertEquals(ChatFmt.LLAMA3, PromptFormat.detect(null, "Meta-Llama-3-8B-Instruct.gguf"))
        assertEquals(ChatFmt.PHI, PromptFormat.detect(null, "phi-3-mini.gguf"))
        assertEquals(ChatFmt.CHATML, PromptFormat.detect(null, "unknown.gguf"))
    }

    @Test fun thinkSwitchesFollowTheModelFamily() {
        PromptFormat.configure("<|im_start|>", "qwen3-4b.gguf")
        assertTrue(PromptFormat.thinkSwitch)
        assertTrue(PromptFormat.emptyThinkBlock)

        PromptFormat.configure("<|im_start|>", "qwen2.5-1.5b-instruct.gguf")
        assertFalse(PromptFormat.thinkSwitch)
        assertFalse(PromptFormat.emptyThinkBlock)

        PromptFormat.configure(null, "DeepSeek-R1-Distill-Qwen-7B.gguf")
        assertEquals(ChatFmt.DEEPSEEK, PromptFormat.fmt)
        assertTrue(PromptFormat.emptyThinkBlock)
    }

    @Test fun qwen3NoThinkAppendsTheSwitchAndPrefillsEmptyBlock() {
        PromptFormat.forceForTest(ChatFmt.CHATML, think = true)
        val p = PromptFormat.render(system, listOf(Turn("user", "hi")), noThink = true)
        assertTrue(p.contains("hi /no_think"))
        assertTrue(p.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
    }

    @Test fun thinkingOnKeepsTheSwitchOff() {
        PromptFormat.forceForTest(ChatFmt.CHATML, think = true)
        val p = PromptFormat.render(system, listOf(Turn("user", "hi")), noThink = false)
        assertFalse(p.contains("/no_think"))
        assertFalse(p.contains("<think>"))
    }

    @Test fun deepseekNoThinkStartsWithAnEmptyThinkBlock() {
        PromptFormat.forceForTest(ChatFmt.DEEPSEEK, think = true)
        val p = PromptFormat.render(system, listOf(Turn("user", "hi")), noThink = true)
        assertTrue(p.endsWith("<\uFF5CAssistant\uFF5C><think>\n\n</think>\n\n"))
    }

    @Test fun plainFamiliesNeverGetAThinkBlock() {
        PromptFormat.forceForTest(ChatFmt.LLAMA3, think = true)
        val p = PromptFormat.render(system, listOf(Turn("user", "hi")), noThink = true)
        assertFalse(p.contains("<think>"))
        assertTrue(p.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
    }

    @Test fun toolResponsesAreNeverGivenTheNoThinkSuffix() {
        PromptFormat.forceForTest(ChatFmt.CHATML, think = true)
        val p = PromptFormat.render(system, listOf(
            Turn("user", "open youtube"),
            Turn("assistant", "calling open_app"),
            Turn("user", "<tool_response>{\"ok\":true}</tool_response>")
        ), noThink = true)
        assertTrue(p.contains("<tool_response>{\"ok\":true}</tool_response>"))
        assertFalse(p.contains("</tool_response> /no_think"))
    }

    @Test fun chatPrefixIsAnExactPrefixOfTheRealPrompt() {
        PromptFormat.forceForTest(ChatFmt.CHATML, think = false)
        val prefix = PromptFormat.chatPrefix(system)
        val full = PromptFormat.render(system, listOf(Turn("user", "hello there")), noThink = false)
        assertTrue(full.startsWith(prefix))
        assertTrue(prefix.contains(system))
    }

    @Test fun everyFamilyContainsTheSystemTextAndTheUserTurn() {
        for (f in ChatFmt.values()) {
            PromptFormat.forceForTest(f, think = false)
            val p = PromptFormat.render(system, listOf(Turn("user", "hello")), noThink = false)
            assertTrue(f.name, p.contains(system))
            assertTrue(f.name, p.contains("hello"))
        }
    }

    @Test fun singleIsSystemPlusOneUserTurn() {
        PromptFormat.forceForTest(ChatFmt.CHATML, think = false)
        val p = PromptFormat.single(system, "hi")
        assertEquals(PromptFormat.render(system, listOf(Turn("user", "hi")), noThink = true), p)
    }
}
