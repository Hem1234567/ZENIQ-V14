package com.astra.agent.hub

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

// ── Trello ───────────────────────────────────────────────────────────────────
internal object TrelloActions {
    private const val API = "https://api.trello.com/1"
    private fun auth(c: PluginCtx) = "key=${Http.enc(c.field("key"))}&token=${Http.enc(c.field("token"))}"
    val actions: List<PluginAction> = listOf(
        act("boards_list", "List the user's Trello boards.", emptyList(), 0) { c, _ ->
            val r = Http.get("$API/members/me/boards?fields=name,url&${auth(c)}").check("Trello boards").array()
            if (r.length() == 0) "No boards." else (0 until r.length()).joinToString("\n") { val b = r.getJSONObject(it); "- ${b.optString("name")} | id=${b.optString("id")}" }
        },
        act("lists_get", "List the columns (lists) of a board.", listOf(p("board_id", "board id")), 0) { c, a ->
            val r = Http.get("$API/boards/${a.need("board_id")}/lists?fields=name&${auth(c)}").check("Trello lists").array()
            (0 until r.length()).joinToString("\n") { val b = r.getJSONObject(it); "- ${b.optString("name")} | id=${b.optString("id")}" }.ifBlank { "No lists." }
        },
        act("cards_list", "List the cards of a board.", listOf(p("board_id", "board id")), 0) { c, a ->
            val r = Http.get("$API/boards/${a.need("board_id")}/cards?fields=name,due,idList&${auth(c)}").check("Trello cards").array()
            (0 until r.length()).joinToString("\n") { val b = r.getJSONObject(it); "- ${b.optString("name")} | due ${b.optString("due", "none")} | id=${b.optString("id")}" }.ifBlank { "No cards." }
        },
        act("card_create", "Create a card in a list.", listOf(p("list_id", "list id"), p("name", "card title"), p("desc", "optional"), p("due", "optional ISO date")), 1) { c, a ->
            val extra = (if (a.optString("desc").isNotBlank()) "&desc=${Http.enc(a.optString("desc"))}" else "") + (if (a.optString("due").isNotBlank()) "&due=${Http.enc(a.optString("due"))}" else "")
            val r = Http.call("POST", "$API/cards?idList=${Http.enc(a.need("list_id"))}&name=${Http.enc(a.need("name"))}$extra&${auth(c)}", body = "").check("Trello card").json()
            if (r.str("id").isEmpty()) throw PluginError("Trello did not return a card id.")
            "Card created: ${r.str("shortUrl")}"
        }
    )
    val health: suspend (PluginCtx) -> String = { c -> "Signed in as " + Http.get("$API/members/me?fields=username&${auth(c)}").check("Trello").json().str("username") }
}

// ── Jira ─────────────────────────────────────────────────────────────────────
internal object JiraActions {
    private fun base(c: PluginCtx) = "https://" + c.field("domain").removePrefix("https://").trim('/')
    private fun h(c: PluginCtx) = mapOf("Authorization" to "Basic " + Base64.encodeToString("${c.field("email")}:${c.field("token")}".toByteArray(), Base64.NO_WRAP))
    private fun adf(text: String) = JSONObject().put("type", "doc").put("version", 1).put("content", JSONArray().put(
        JSONObject().put("type", "paragraph").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text.ifBlank { " " })))))
    val actions: List<PluginAction> = listOf(
        act("issues_search", "Search Jira issues with JQL.", listOf(p("jql", "e.g. assignee = currentUser() AND statusCategory != Done")), 0) { c, a ->
            val url = base(c) + "/rest/api/3/search/jql?maxResults=20&fields=summary,status,assignee&jql=" + Http.enc(a.need("jql"))
            val r = Http.get(url, h(c)).check("Jira search").json().optJSONArray("issues")
            if (r == null || r.length() == 0) "No issues found."
            else (0 until r.length()).joinToString("\n") { i -> val x = r.getJSONObject(i); val f = x.optJSONObject("fields")
                "- ${x.optString("key")} | ${f?.optString("summary")} | ${f?.optJSONObject("status")?.optString("name")} | ${f?.optJSONObject("assignee")?.optString("displayName") ?: "unassigned"}" }
        },
        act("issue_create", "Create a Jira issue.", listOf(p("project_key", "e.g. PROJ"), p("summary", "title"), p("description", "text"), p("type", "Task, Bug, Story (default Task)")), 1) { c, a ->
            val fields = JSONObject().put("project", JSONObject().put("key", a.need("project_key"))).put("summary", a.need("summary"))
                .put("description", adf(a.optString("description"))).put("issuetype", JSONObject().put("name", a.optString("type", "Task").ifBlank { "Task" }))
            val r = Http.postJson(base(c) + "/rest/api/3/issue", JSONObject().put("fields", fields), h(c)).check("Jira create").json()
            if (r.str("key").isEmpty()) throw PluginError("Jira did not return an issue key.")
            "Issue ${r.str("key")} created: ${base(c)}/browse/${r.str("key")}"
        }
    )
    val health: suspend (PluginCtx) -> String = { c -> "Signed in as " + Http.get(base(c) + "/rest/api/3/myself", h(c)).check("Jira").json().str("displayName") }
}

// ── Dropbox ──────────────────────────────────────────────────────────────────
internal object DropboxActions {
    private fun h(c: PluginCtx) = mapOf("Authorization" to "Bearer ${c.field("token")}")
    val actions: List<PluginAction> = listOf(
        act("list_folder", "List files in a Dropbox folder.", listOf(p("path", "folder path, empty for the root")), 0) { c, a ->
            val r = Http.postJson("https://api.dropboxapi.com/2/files/list_folder", JSONObject().put("path", a.optString("path")).put("limit", 30), h(c)).check("Dropbox list").json().optJSONArray("entries")
            if (r == null || r.length() == 0) "(empty)" else (0 until r.length()).joinToString("\n") { val e = r.getJSONObject(it); "- ${e.optString(".tag")} | ${e.optString("path_display")}" }
        },
        act("search", "Search Dropbox files by name.", listOf(p("query", "words to look for")), 0) { c, a ->
            val r = Http.postJson("https://api.dropboxapi.com/2/files/search_v2", JSONObject().put("query", a.need("query")), h(c)).check("Dropbox search").json().optJSONArray("matches")
            if (r == null || r.length() == 0) "No files found."
            else (0 until r.length()).joinToString("\n") { val m = r.getJSONObject(it).optJSONObject("metadata")?.optJSONObject("metadata"); "- ${m?.optString("path_display")}" }
        },
        act("read_text", "Read a text file from Dropbox.", listOf(p("path", "file path")), 0) { c, a ->
            val hh = h(c) + ("Dropbox-API-Arg" to JSONObject().put("path", a.need("path")).toString())
            Http.call("POST", "https://content.dropboxapi.com/2/files/download", hh, body = "", contentType = "text/plain").check("Dropbox download").body.take(12000)
        },
        act("upload_text", "Create or replace a text file in Dropbox.", listOf(p("path", "e.g. /notes/today.txt"), p("text", "file content")), 1) { c, a ->
            val hh = h(c) + ("Dropbox-API-Arg" to JSONObject().put("path", a.need("path")).put("mode", "overwrite").toString())
            val r = Http.call("POST", "https://content.dropboxapi.com/2/files/upload", hh, a.optString("text"), "application/octet-stream").check("Dropbox upload").json()
            "Saved ${r.str("path_display")} (${r.optLong("size")} bytes)."
        }
    )
    val health: suspend (PluginCtx) -> String = { c ->
        val r = Http.postJson("https://api.dropboxapi.com/2/check/user", JSONObject().put("query", "zeniq"), h(c)).check("Dropbox")
        if (r.json().str("result") == "zeniq") "Token is valid" else "Token accepted"
    }
}

// ── Discord ──────────────────────────────────────────────────────────────────
internal object DiscordActions {
    private fun h(c: PluginCtx) = mapOf("Authorization" to "Bot ${c.field("token")}")
    val actions: List<PluginAction> = listOf(
        act("channel_messages", "Read recent messages of a channel (bot token).", listOf(p("channel_id", "channel id"), p("limit", "1-30")), 0) { c, a ->
            val r = Http.get("https://discord.com/api/v10/channels/${a.need("channel_id")}/messages?limit=${a.optInt("limit", 10).coerceIn(1, 30)}", h(c)).check("Discord messages").array()
            (0 until r.length()).joinToString("\n") { val m = r.getJSONObject(it); "- ${m.optJSONObject("author")?.optString("username")}: ${m.optString("content").take(300)}" }.ifBlank { "No messages." }
        },
        act("channel_send", "SEND a message to a channel with the bot token.", listOf(p("channel_id", "channel id"), p("content", "message")), 2) { c, a ->
            val r = Http.postJson("https://discord.com/api/v10/channels/${a.need("channel_id")}/messages", JSONObject().put("content", a.need("content").take(1990)), h(c)).check("Discord send").json()
            if (r.str("id").isEmpty()) throw PluginError("Discord did not return a message id.")
            "Message sent (id ${r.str("id")})."
        },
        act("webhook_post", "SEND a message through the saved webhook URL.", listOf(p("content", "message")), 2) { c, a ->
            val url = c.field("webhook")
            if (url.isBlank()) throw PluginError("No webhook URL saved. Add one when connecting Discord.")
            Http.postJson("$url?wait=true", JSONObject().put("content", a.need("content").take(1990))).check("Discord webhook")
            "Message sent through the webhook."
        }
    )
    val health: suspend (PluginCtx) -> String = { c ->
        if (c.field("token").isNotBlank()) "Bot " + Http.get("https://discord.com/api/v10/users/@me", h(c)).check("Discord").json().str("username")
        else if (c.field("webhook").isNotBlank()) "Webhook " + Http.get(c.field("webhook")).check("Discord webhook").json().str("name")
        else throw PluginError("Enter a bot token or a webhook URL.")
    }
}

// ── Telegram ─────────────────────────────────────────────────────────────────
internal object TelegramActions {
    private fun api(c: PluginCtx, m: String) = "https://api.telegram.org/bot${c.field("token")}/$m"
    private fun ok(r: HttpResult, what: String): JSONObject {
        val j = r.json()
        if (!r.ok || !j.optBoolean("ok")) throw PluginError("$what failed: ${j.optString("description", r.body.take(160))}")
        return j
    }
    val actions: List<PluginAction> = listOf(
        act("get_updates", "Read the latest messages sent to the bot (shows chat ids).", emptyList(), 0) { c, _ ->
            val r = ok(Http.get(api(c, "getUpdates?limit=15")), "Telegram updates").optJSONArray("result")
            if (r == null || r.length() == 0) "No new messages. Send a message to your bot first."
            else (0 until r.length()).joinToString("\n") { i ->
                val m = r.getJSONObject(i).optJSONObject("message"); "- chat_id=${m?.optJSONObject("chat")?.optLong("id")} | ${m?.optJSONObject("from")?.optString("first_name")}: ${m?.optString("text")?.take(200)}"
            }
        },
        act("send_message", "SEND a Telegram message from the bot.", listOf(p("chat_id", "chat id from get_updates"), p("text", "message")), 2) { c, a ->
            val j = ok(Http.postJson(api(c, "sendMessage"), JSONObject().put("chat_id", a.need("chat_id")).put("text", a.need("text"))), "Telegram send")
            if (j.optJSONObject("result")?.optLong("message_id", 0L) == 0L) throw PluginError("Telegram did not confirm the message.")
            "Message sent (id ${j.optJSONObject("result")?.optLong("message_id")})."
        }
    )
    val health: suspend (PluginCtx) -> String = { c -> "Bot @" + ok(Http.get(api(c, "getMe")), "Telegram").optJSONObject("result")?.optString("username") }
}
