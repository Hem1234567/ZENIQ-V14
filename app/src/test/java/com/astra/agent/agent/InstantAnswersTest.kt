package com.astra.agent.agent

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Replies that need no model at all. Pure text matching, so they are tested here. */
class InstantAnswersTest {
    private val now = LocalDateTime.of(2026, 11, 19, 15, 30)

    @Test fun greetingsAndThanks() {
        assertEqualsContains("Hi!", "hello")
        assertEqualsContains("welcome", "thank you")
        assertEqualsContains("Bye", "goodbye")
    }

    @Test fun socialSmallTalk() {
        assertTrue(InstantAnswers.answer("how are you", now)!!.contains("I'm good"))
        assertTrue(InstantAnswers.answer("what can you do", now)!!.contains("control your phone"))
        assertTrue(InstantAnswers.answer("help", now)!!.contains("I can"))
    }

    @Test fun clockAndDateUseTheRealValues() {
        assertTrue(InstantAnswers.answer("what time is it", now)!!.contains("3:30"))
        assertTrue(InstantAnswers.answer("what's the date", now)!!.contains("19 November 2026"))
    }

    @Test fun arithmeticIsComputed() {
        assertEqualsContains("= 13", "7 + 6")
        assertEqualsContains("= 24", "(2+4)*4")
    }

    @Test fun realQuestionsGoToTheModel() {
        assertNull(InstantAnswers.answer("what is the capital of france", now))
        assertNull(InstantAnswers.answer("how are you doing today", now))
        assertNull(InstantAnswers.answer("write a poem about the sea", now))
    }

    private fun assertEqualsContains(part: String, ask: String) {
        assertNotNull("no instant answer for \"$ask\"", InstantAnswers.answer(ask, now))
        assertTrue("\"$part\" not in \"${InstantAnswers.answer(ask, now)}\"", InstantAnswers.answer(ask, now)!!.contains(part))
    }
}
