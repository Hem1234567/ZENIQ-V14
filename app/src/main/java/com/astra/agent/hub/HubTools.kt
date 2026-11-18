package com.astra.agent.hub

import android.content.Context
import com.astra.agent.agent.Param
import com.astra.agent.agent.ToolDef
import org.json.JSONObject

/** Starts the hub modules and gives the chat agent three tools: plugin_list, plugin_call and skill_load. */
object Hub {
    @Volatile private var started = false
    fun init(ctx: Context) {
        if (started) return
        started = true
        SkillEngine.init(ctx); PluginHub.init(ctx); AutomationStore.init(ctx)
    }

    private fun argsOf(a: JSONObject): JSONObject {
        return when (val raw = a.opt("args")) {
            is JSONObject -> raw
            is String -> try { JSONObject(raw.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
            else -> JSONObject()
        }
    }

    fun defs(): List<ToolDef> = listOf(
        ToolDef("plugin_list", "List connected plugins (Google, GitHub, Slack, Notion, Microsoft, Trello, Jira, Dropbox, Discord, Telegram) and the actions each can do.",
            emptyList(), false) { _ ->
            val all = PluginHub.all().filter { it.impl == Impl.WORKING }
            val ready = all.filter { PluginHub.isReady(it.id) }
            (if (ready.isEmpty()) "No plugin is connected. The user can connect one with the + button in chat > Plugins.\n" else "Connected:\n" + PluginHub.actionDoc(ready) + "\n") +
                "Not connected: " + all.filter { !PluginHub.isReady(it.id) }.joinToString { it.id }
        },
        ToolDef("plugin_call", "Run an action of a connected plugin, for example plugin=github action=repo_activity args={\"repo\":\"owner/name\"}. Sending or deleting asks the user first. Check the result: it starts with OK, ERROR or CANCELLED; never say it worked unless it starts with OK.",
            listOf(Param("plugin", "string", "plugin id, e.g. google, github, slack, notion"), Param("action", "string", "action id from plugin_list"), Param("args", "string", "JSON object with the action's arguments")), false) { a ->
            PluginHub.call(a.optString("plugin").trim().lowercase(), a.optString("action").trim(), argsOf(a))
        },
        ToolDef("skill_load", "Load the working instructions of a skill by name or topic (e.g. 'powerpoint', 'debugging', 'deep research').",
            listOf(Param("query", "string", "skill name or topic")), false) { a ->
            val picks = SkillEngine.select(a.optString("query"), 2)
            if (picks.isEmpty()) "No skill matched. Skills: " + SkillEngine.all().take(40).joinToString { it.id }
            else picks.joinToString("\n") { SkillEngine.instructionBlock(it.skill) }
        }
    )
}
