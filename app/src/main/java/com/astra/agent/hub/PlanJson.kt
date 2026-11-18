package com.astra.agent.hub

import org.json.JSONArray
import org.json.JSONObject

/** Saves and restores an approved plan (agents, steps, skills, plugins and the user's edits). */
object PlanJson {
    fun toJson(p: Plan): JSONObject = JSONObject()
        .put("goal", p.goal).put("skills", JSONArray(p.skills)).put("plugins", JSONArray(p.plugins)).put("notes", JSONArray(p.notes))
        .put("usedModel", p.usedModel).put("edited", p.edited)
        .put("steps", JSONArray(p.steps.map { s ->
            JSONObject().put("id", s.id).put("agent", s.agent).put("kind", s.kind.name).put("title", s.title).put("deps", JSONArray(s.dependsOn))
                .put("skills", JSONArray(s.skills)).put("tool", s.tool).put("plugin", s.plugin).put("action", s.action)
                .put("args", s.args).put("instruction", s.instruction).put("format", s.format)
        }))

    private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

    fun fromJson(o: JSONObject): Plan? {
        val arr = o.optJSONArray("steps") ?: return null
        val steps = (0 until arr.length()).mapNotNull { i ->
            val x = arr.optJSONObject(i) ?: return@mapNotNull null
            val kind = try { StepKind.valueOf(x.optString("kind")) } catch (_: Exception) { return@mapNotNull null }
            Step(x.optString("id"), x.optString("agent"), kind, x.optString("title"), strs(x.optJSONArray("deps")), strs(x.optJSONArray("skills")),
                x.optString("tool"), x.optString("plugin"), x.optString("action"), x.optJSONObject("args") ?: JSONObject(), x.optString("instruction"), x.optString("format"))
        }
        if (steps.isEmpty()) return null
        return Plan(o.optString("goal"), steps, strs(o.optJSONArray("skills")), strs(o.optJSONArray("plugins")), strs(o.optJSONArray("notes")), o.optBoolean("usedModel")).also { it.edited = o.optBoolean("edited") }
    }

    fun fromString(s: String): Plan? = try { if (s.isBlank()) null else fromJson(JSONObject(s)) } catch (_: Exception) { null }

    /** A fresh copy with every step reset to "waiting" (used before each run). */
    fun fresh(p: Plan): Plan = fromJson(toJson(p)) ?: p

    /** Problems that must be fixed before a plan can be approved (empty = fine). */
    fun validate(p: Plan): List<String> {
        val out = mutableListOf<String>()
        val work = p.steps.filter { it.kind != StepKind.VERIFY }
        if (work.isEmpty()) out += "The plan has no steps."
        val ids = p.steps.map { it.id }
        if (ids.size != ids.toSet().size) out += "Two steps share the same id."
        for (s in p.steps) {
            if (s.dependsOn.any { it !in ids }) out += "'${s.title}' depends on a step that no longer exists."
            if (s.dependsOn.contains(s.id)) out += "'${s.title}' depends on itself."
            when (s.kind) {
                StepKind.SKILL, StepKind.DOC, StepKind.DEVICE, StepKind.BROWSER -> if (s.instruction.isBlank()) out += "'${s.title}' needs an instruction."
                StepKind.PLUGIN -> if (PluginHub.plugin(s.plugin)?.actions?.none { it.id == s.action } != false) out += "'${s.title}' uses an unknown plugin action."
                StepKind.TOOL -> if (s.tool.isBlank()) out += "'${s.title}' needs a tool."
                StepKind.VERIFY -> {}
            }
        }
        return out
    }
}

/** "@auto <prompt>" typed in the chat box. */
object AutoCommand {
    class Parsed(val prompt: String)
    /** Returns null when the text is not an @auto command. An empty prompt is returned as an empty string. */
    fun parse(text: String): Parsed? {
        val t = text.trim()
        if (!t.startsWith("@auto", ignoreCase = true)) return null
        val rest = t.substring(5)
        if (rest.isNotEmpty() && !rest[0].isWhitespace() && rest[0] != ':') return null   // "@automatic" is not a command
        return Parsed(rest.trimStart(':', ' ', '\n', '\t').trim())
    }
}
