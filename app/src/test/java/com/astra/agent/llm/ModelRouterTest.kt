package com.astra.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intent classification and model choice. Regex + arithmetic only, no Android. */
class ModelRouterTest {
    private fun m(name: String, size: Double, loaded: Boolean = false) = ModelRouter.profileOf(name, size, loaded)

    // ── classification ───────────────────────────────────────────────────
    @Test fun attachmentsWin() {
        assertEquals(RouteRole.VISION, ModelRouter.roleFor("what is this?", hasImage = true))
        assertEquals(RouteRole.DOCUMENT, ModelRouter.roleFor("summarise", hasDoc = true))
    }

    @Test fun codingPrompts() {
        assertEquals(RouteRole.CODING, ModelRouter.roleFor("write a python function to sort a list"))
        assertEquals(RouteRole.CODING, ModelRouter.roleFor("help me debug this kotlin class"))
        assertEquals(RouteRole.CODING, ModelRouter.roleFor("build me a website"))
    }

    @Test fun toolPrompts() {
        assertEquals(RouteRole.TOOLS, ModelRouter.roleFor("open YouTube"))
        assertEquals(RouteRole.TOOLS, ModelRouter.roleFor("send a message to mom"))
        assertEquals(RouteRole.TOOLS, ModelRouter.roleFor("search the web for lofi music"))
    }

    @Test fun reasoningAndTranslation() {
        assertEquals(RouteRole.REASONING, ModelRouter.roleFor("solve 2x + 5 = 17 step by step"))
        assertEquals(RouteRole.TRANSLATION, ModelRouter.roleFor("translate this to tamil"))
    }

    @Test fun simpleAndLongChat() {
        assertEquals(RouteRole.CHAT_SIMPLE, ModelRouter.roleFor("hello"))
        assertEquals(RouteRole.CHAT_COMPLEX, ModelRouter.roleFor("word ".repeat(60)))
        assertEquals(RouteRole.CHAT_COMPLEX, ModelRouter.roleFor("explain how a transformer works"))
    }

    // ── capability inference ─────────────────────────────────────────────
    @Test fun coderFilesAreCodingModels() {
        val p = m("qwen2.5-coder-3b-instruct.gguf", 2.0)
        assertTrue(RouteRole.CODING in p.roles)
        assertTrue(!p.thinkingOnly)
    }

    @Test fun reasoningFilesAreThinkingOnly() {
        val p = m("DeepSeek-R1-Distill-Qwen-7B.gguf", 4.5)
        assertTrue(p.thinkingOnly)
        assertTrue(RouteRole.REASONING in p.roles)
        assertTrue(RouteRole.TOOLS !in p.roles)
    }

    @Test fun visionFilesAreVisionModels() {
        assertTrue(RouteRole.VISION in m("MiniCPM-V-2.6.gguf", 5.5).roles)
        assertTrue(RouteRole.VISION in m("llava-1.6-mistral-7b.gguf", 4.1).roles)
    }

    // ── routing ──────────────────────────────────────────────────────────
    @Test fun emptyInstallReturnsNull() {
        assertNull(ModelRouter.route("hello", emptyList()))
        assertNull(ModelRouter.route("hello", listOf(m("a.gguf", 1.0)), forced = "other.gguf"))
    }

    @Test fun forcedModelAlwaysWinsAndSaysSo() {
        val models = listOf(m("coder.gguf", 2.0), m("chat.gguf", 1.0))
        val d = ModelRouter.route("write a kotlin function", models, forced = "chat.gguf")!!
        assertEquals("chat.gguf", d.model)
        assertTrue(d.reason.startsWith("chosen by you"))
        assertNull(ModelRouter.route("x", models, forced = "missing.gguf"))
    }

    @Test fun smallerModelWinsTheSameRole() {
        val d = ModelRouter.route("open YouTube", listOf(m("big.gguf", 4.0), m("small.gguf", 1.0)))!!
        assertEquals("small.gguf", d.model)
    }

    @Test fun loadedModelIsPreferredToAvoidAReload() {
        val models = listOf(m("qwen2.5-1.5b-instruct.gguf", 1.5), m("llama-3.2-3b-instruct.gguf", 3.0, loaded = true))
        val d = ModelRouter.route("open YouTube", models)!!
        assertEquals("llama-3.2-3b-instruct.gguf", d.model)
        assertEquals("already loaded", d.reason)
    }

    @Test fun loadedModelStaysEvenWhenASmallerOneWouldBeFaster() {
        // Reloading costs seconds; switching for a small size gain makes every message slower, not faster.
        val models = listOf(m("big.gguf", 4.0, loaded = true), m("small.gguf", 1.0))
        val d = ModelRouter.route("hello", models)!!
        assertEquals("big.gguf", d.model)
        assertEquals("already loaded", d.reason)
    }

    @Test fun loadedModelIsReplacedWhenItCannotDoTheJob() {
        val models = listOf(m("DeepSeek-R1-7B.gguf", 4.5, loaded = true), m("qwen2.5-1.5b-instruct.gguf", 1.5))
        val d = ModelRouter.route("open YouTube", models)!!
        assertEquals("qwen2.5-1.5b-instruct.gguf", d.model)
    }

    @Test fun thinkingOnlyModelIsAvoidedForToolsWhenAnotherExists() {
        val models = listOf(m("DeepSeek-R1-7B.gguf", 4.5), m("qwen2.5-1.5b-instruct.gguf", 1.5))
        assertEquals("qwen2.5-1.5b-instruct.gguf", ModelRouter.route("open YouTube", models)!!.model)
    }

    @Test fun thinkingOnlyModelIsStillUsedWhenItIsAllWeHave() {
        val r1 = m("DeepSeek-R1-7B.gguf", 4.5)
        val d = ModelRouter.route("write a python function", listOf(r1))!!
        assertEquals("DeepSeek-R1-7B.gguf", d.model)
        assertTrue(d.reason.contains("no coding model installed"))
    }

    @Test fun coderIsChosenForCodeWhenANormalModelAlsoCant() {
        // Both can code (small instruct models get CODING too), but the coder is larger, so size decides.
        val models = listOf(m("qwen2.5-0.5b-instruct.gguf", 0.5), m("qwen2.5-coder-1.5b.gguf", 1.5))
        val d = ModelRouter.route("write a python function", models)!!
        assertEquals("qwen2.5-0.5b-instruct.gguf", d.model)
        assertEquals(RouteRole.CODING, d.role)
    }
}
