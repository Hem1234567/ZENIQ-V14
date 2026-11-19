package com.astra.agent.llm

/** What a prompt needs from a model. */
enum class RouteRole { CHAT_SIMPLE, CHAT_COMPLEX, CODING, TOOLS, VISION, REASONING, DOCUMENT, TRANSLATION }

/** A downloaded model as the router sees it. */
data class RouterModel(
    val name: String,
    val sizeGb: Double,
    val roles: Set<RouteRole>,
    val thinkingOnly: Boolean = false,
    val loaded: Boolean = false
)

data class Decision(val model: String, val role: RouteRole, val reason: String)

/**
 * Picks a model for a prompt without calling any model. Classification is regex + length, so it costs
 * microseconds. A forced (manually picked) model always wins; the router only warns when it is unsuitable.
 */
object ModelRouter {
    private val codeRe = Regex(
        "\\b(code|coding|function|method|class|debug|compile|build|program|script|syntax|" +
        "python|kotlin|java|javascript|typescript|html|css|sql|gradle|xml|flutter|react|node|express|flask|fastapi|" +
        "algorithm|api|endpoint|bug|refactor|unit test|implementation)\\b", RegexOption.IGNORE_CASE)
    private val buildRe = Regex("\\b(app|application|website|web app|game|bot|full[ -]?stack|backend|frontend|project)\\b", RegexOption.IGNORE_CASE)
    private val toolRe = Regex(
        "\\b(open|launch|tap|click|call|dial|send|message|sms|email|remind|reminder|schedule|alarm|timer|" +
        "search|browse|play|navigate|directions|download|take a photo|photo|screenshot|turn on|turn off|set|type|write to|" +
        "read my|check my|calendar|whatsapp|telegram|youtube)\\b", RegexOption.IGNORE_CASE)
    private val mathRe = Regex("\\b(solve|prove|equation|math|calculate|logic|step by step|derivation|probability|why)\\b", RegexOption.IGNORE_CASE)
    private val translateRe = Regex("\\b(translate|translation|in tamil|in hindi|in telugu)\\b", RegexOption.IGNORE_CASE)
    private val longRe = Regex("\\b(explain|detail|essay|article|summary|summarize|compare|guide|plan|story|letter)\\b", RegexOption.IGNORE_CASE)

    /** Classification is ordered: attachments first, then the most demanding intent. */
    fun roleFor(prompt: String, hasImage: Boolean = false, hasDoc: Boolean = false, hasTools: Boolean = false): RouteRole {
        if (hasImage) return RouteRole.VISION
        if (hasDoc) return RouteRole.DOCUMENT
        if (translateRe.containsMatchIn(prompt)) return RouteRole.TRANSLATION
        if (codeRe.containsMatchIn(prompt) || buildRe.containsMatchIn(prompt)) return RouteRole.CODING
        if (mathRe.containsMatchIn(prompt)) return RouteRole.REASONING
        if (hasTools || toolRe.containsMatchIn(prompt)) return RouteRole.TOOLS
        if (prompt.length > 220 || longRe.containsMatchIn(prompt)) return RouteRole.CHAT_COMPLEX
        return RouteRole.CHAT_SIMPLE
    }

    private fun thinkingOnly(name: String): Boolean {
        val n = name.lowercase()
        return Regex("r1|reason|think|qwq").containsMatchIn(n) &&
            !Regex("instruct|chat|coder").containsMatchIn(n)
    }

    /** Capabilities inferred from the file name: no catalog entry needed. */
    fun profileOf(name: String, sizeGb: Double, loaded: Boolean = false): RouterModel {
        val n = name.lowercase()
        val roles = HashSet<RouteRole>()
        if (Regex("coder|code|starcoder").containsMatchIn(n)) roles += RouteRole.CODING
        if (Regex("r1|reason|think|qwq").containsMatchIn(n)) roles += RouteRole.REASONING
        if (Regex("vision|vl|llava|minicpm-v|gemma-3|moondream").containsMatchIn(n)) roles += RouteRole.VISION
        val thinkOnly = thinkingOnly(name)
        if (!thinkOnly) {
            roles += RouteRole.TOOLS
            roles += RouteRole.CHAT_SIMPLE
            roles += RouteRole.CHAT_COMPLEX
        }
        if (sizeGb in 0.1..2.6) roles += RouteRole.CODING   // small instruct models write simple code fine
        roles += RouteRole.DOCUMENT
        if (Regex("qwen|gemma|llama|phi|smollm").containsMatchIn(n)) roles += RouteRole.TRANSLATION
        return RouterModel(name, sizeGb, roles, thinkOnly, loaded)
    }

    private fun canDo(m: RouterModel, role: RouteRole): Boolean = when (role) {
        RouteRole.CODING, RouteRole.TOOLS -> !m.thinkingOnly && role in m.roles
        else -> role in m.roles
    }

    private fun score(m: RouterModel, role: RouteRole): Int {
        var s = if (role in m.roles) 100 else 0
        s += (40 - (m.sizeGb * 6).toInt()).coerceAtLeast(0)   // smaller is faster on a phone
        if (m.loaded) s += 25                                 // stickiness: avoid a reload
        if (role == RouteRole.REASONING && m.thinkingOnly) s += 30
        return s
    }

    /**
     * [forced] is a model the user picked: it is returned unchanged whenever it is installed.
     * Returns null when no model is installed at all.
     */
    fun route(
        prompt: String,
        models: List<RouterModel>,
        hasImage: Boolean = false,
        hasDoc: Boolean = false,
        hasTools: Boolean = false,
        forced: String? = null
    ): Decision? {
        if (forced != null) {
            val f = models.firstOrNull { it.name == forced } ?: return null
            val role = roleFor(prompt, hasImage, hasDoc, hasTools)
            val reason = if (canDo(f, role)) "chosen by you" else "chosen by you (not ideal for ${role.name.lowercase()})"
            return Decision(f.name, role, reason)
        }
        if (models.isEmpty()) return null
        val role = roleFor(prompt, hasImage, hasDoc, hasTools)
        // Speed first: loading a model file takes seconds on a phone, which is far more than the answer saves.
        // The loaded model therefore stays unless it truly cannot do this request (a picture with a text-only
        // model, phone tools with a thinking-only model).
        val loaded = models.firstOrNull { it.loaded }
        if (loaded != null && canDo(loaded, role)) return Decision(loaded.name, role, "already loaded")
        val usable = models.filter { canDo(it, role) }
        val pool = usable.ifEmpty { models.filter { !it.thinkingOnly } }.ifEmpty { models }
        val best = pool.maxByOrNull { score(it, role) } ?: return null
        val reason = if (usable.isEmpty()) "no ${role.name.lowercase()} model installed" else "best fit for ${role.name.lowercase()}"
        return Decision(best.name, role, reason)
    }
}
