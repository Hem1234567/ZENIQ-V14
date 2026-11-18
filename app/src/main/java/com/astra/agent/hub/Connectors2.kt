package com.astra.agent.hub

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

// ── GitHub ───────────────────────────────────────────────────────────────────
internal object GitHubActions {
    private const val API = "https://api.github.com"
    private fun h(c: PluginCtx) = mapOf(
        "Authorization" to "Bearer ${c.field("token")}", "Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28"
    )
    private suspend fun repoOf(c: PluginCtx, a: JSONObject): String {
        val r = a.need("repo").removePrefix("https://github.com/").trim('/')
        if (r.contains('/')) return r
        val login = Http.get("$API/user", h(c)).check("GitHub user").json().str("login")
        return "$login/$r"
    }
    private fun arrLines(arr: JSONArray, f: (JSONObject) -> String) = if (arr.length() == 0) "(none)" else (0 until arr.length()).joinToString("\n") { f(arr.getJSONObject(it)) }

    val actions: List<PluginAction> = listOf(
        act("repos_list", "List the user's repositories (most recently updated first).", listOf(p("max", "1-30, default 15")), 0) { c, a ->
            val r = Http.get("$API/user/repos?sort=updated&per_page=${a.optInt("max", 15).coerceIn(1, 30)}", h(c)).check("GitHub repos").array()
            arrLines(r) { "- ${it.optString("full_name")} | ${if (it.optBoolean("private")) "private" else "public"} | updated ${it.optString("pushed_at")} | ${it.optString("description")}" }
        },
        act("commits_list", "List recent commits of a repository.", listOf(p("repo", "owner/name or just name"), p("max", "1-30, default 10")), 0) { c, a ->
            val r = Http.get("$API/repos/${repoOf(c, a)}/commits?per_page=${a.optInt("max", 10).coerceIn(1, 30)}", h(c)).check("GitHub commits").array()
            arrLines(r) { val cm = it.optJSONObject("commit"); "- ${it.optString("sha").take(7)} | ${cm?.optJSONObject("author")?.optString("name")} | ${cm?.optJSONObject("author")?.optString("date")} | ${cm?.optString("message")?.lineSequence()?.firstOrNull()}" }
        },
        act("issues_list", "List issues of a repository.", listOf(p("repo", "owner/name"), p("state", "open, closed or all (default open)")), 0) { c, a ->
            val r = Http.get("$API/repos/${repoOf(c, a)}/issues?per_page=20&state=${a.optString("state", "open").ifBlank { "open" }}", h(c)).check("GitHub issues").array()
            arrLines(r) { if (it.has("pull_request")) "- PR #${it.optInt("number")} ${it.optString("title")} [${it.optString("state")}]" else "- #${it.optInt("number")} ${it.optString("title")} [${it.optString("state")}] by ${it.optJSONObject("user")?.optString("login")}" }
        },
        act("prs_list", "List pull requests of a repository.", listOf(p("repo", "owner/name"), p("state", "open, closed or all")), 0) { c, a ->
            val r = Http.get("$API/repos/${repoOf(c, a)}/pulls?per_page=20&state=${a.optString("state", "open").ifBlank { "open" }}", h(c)).check("GitHub pulls").array()
            arrLines(r) { "- #${it.optInt("number")} ${it.optString("title")} [${it.optString("state")}] by ${it.optJSONObject("user")?.optString("login")}" }
        },
        act("file_read", "Read a text file from a repository.", listOf(p("repo", "owner/name"), p("path", "file path, e.g. README.md")), 0) { c, a ->
            val j = Http.get("$API/repos/${repoOf(c, a)}/contents/${a.need("path")}", h(c)).check("GitHub file").json()
            if (j.optString("encoding") != "base64") throw PluginError("That path is a folder or too large to read.")
            String(Base64.decode(j.optString("content").replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8).take(12000)
        },
        act("repo_activity", "Summarize recent activity: latest commits, open issues and open pull requests.", listOf(p("repo", "owner/name")), 0) { c, a ->
            val repo = repoOf(c, a)
            val commits = Http.get("$API/repos/$repo/commits?per_page=10", h(c)).check("GitHub commits").array()
            val issues = Http.get("$API/repos/$repo/issues?per_page=10&state=open", h(c)).check("GitHub issues").array()
            val prs = Http.get("$API/repos/$repo/pulls?per_page=10&state=open", h(c)).check("GitHub pulls").array()
            "Repository $repo\n\nLatest commits:\n" + arrLines(commits) { val cm = it.optJSONObject("commit"); "- ${it.optString("sha").take(7)} ${cm?.optString("message")?.lineSequence()?.firstOrNull()} (${cm?.optJSONObject("author")?.optString("date")})" } +
                "\n\nOpen issues:\n" + arrLines(issues) { if (it.has("pull_request")) "" else "- #${it.optInt("number")} ${it.optString("title")}" }.ifBlank { "(none)" } +
                "\n\nOpen pull requests:\n" + arrLines(prs) { "- #${it.optInt("number")} ${it.optString("title")}" }
        },
        act("issue_create", "Create an issue in a repository.", listOf(p("repo", "owner/name"), p("title", "issue title"), p("body", "issue text")), 1) { c, a ->
            val r = Http.postJson("$API/repos/${repoOf(c, a)}/issues", JSONObject().put("title", a.need("title")).put("body", a.optString("body")), h(c)).check("GitHub issue").json()
            if (r.optInt("number") == 0) throw PluginError("GitHub did not return an issue number.")
            "Issue #${r.optInt("number")} created: ${r.str("html_url")}"
        },
        act("issue_comment", "Comment on an issue or pull request.", listOf(p("repo", "owner/name"), p("number", "issue number"), p("body", "comment")), 1) { c, a ->
            val r = Http.postJson("$API/repos/${repoOf(c, a)}/issues/${a.need("number")}/comments", JSONObject().put("body", a.need("body")), h(c)).check("GitHub comment").json()
            "Comment posted: ${r.str("html_url")}"
        }
    )
    val health: suspend (PluginCtx) -> String = { c -> "Signed in as " + Http.get("$API/user", h(c)).check("GitHub").json().str("login") }
}

// ── Notion ───────────────────────────────────────────────────────────────────
internal object NotionActions {
    private const val API = "https://api.notion.com/v1"
    private fun h(c: PluginCtx) = mapOf("Authorization" to "Bearer ${c.field("token")}", "Notion-Version" to "2022-06-28")

    private fun titleOf(o: JSONObject): String {
        val props = o.optJSONObject("properties")
        if (props != null) for (k in props.keys()) {
            val pr = props.optJSONObject(k) ?: continue
            if (pr.optString("type") == "title") { val t = pr.optJSONArray("title"); if (t != null && t.length() > 0) return (0 until t.length()).joinToString("") { t.getJSONObject(it).optString("plain_text") } }
        }
        val t = o.optJSONArray("title")
        if (t != null && t.length() > 0) return (0 until t.length()).joinToString("") { t.getJSONObject(it).optString("plain_text") }
        return "(untitled)"
    }

    private fun richText(text: String): JSONArray = JSONArray().put(JSONObject().put("type", "text").put("text", JSONObject().put("content", text.take(1900))))

    val actions: List<PluginAction> = listOf(
        act("search", "Search pages and databases the integration can see.", listOf(p("query", "words to look for")), 0) { c, a ->
            val r = Http.postJson("$API/search", JSONObject().put("query", a.optString("query")).put("page_size", 10), h(c)).check("Notion search").json().optJSONArray("results")
            if (r == null || r.length() == 0) "Nothing found. Share the page or database with your integration in Notion (... > Connections)."
            else (0 until r.length()).joinToString("\n") { i -> val o = r.getJSONObject(i); "- ${o.optString("object")} | ${titleOf(o)} | id=${o.optString("id")}" }
        },
        act("page_read", "Read the text of a Notion page.", listOf(p("page_id", "id from search")), 0) { c, a ->
            val r = Http.get("$API/blocks/${a.need("page_id")}/children?page_size=100", h(c)).check("Notion page").json().optJSONArray("results") ?: return@act "(empty page)"
            val sb = StringBuilder()
            for (i in 0 until r.length()) {
                val b = r.getJSONObject(i); val type = b.optString("type")
                val rt = b.optJSONObject(type)?.optJSONArray("rich_text")
                val line = if (rt != null) (0 until rt.length()).joinToString("") { rt.getJSONObject(it).optString("plain_text") } else ""
                if (line.isNotBlank()) sb.append(when (type) { "heading_1" -> "# "; "heading_2" -> "## "; "heading_3" -> "### "; "bulleted_list_item" -> "- "; "to_do" -> "[ ] "; else -> "" }).append(line).append('\n')
            }
            sb.toString().ifBlank { "(no text blocks)" }.take(12000)
        },
        act("db_query", "List rows of a Notion database.", listOf(p("database_id", "database id from search")), 0) { c, a ->
            val r = Http.postJson("$API/databases/${a.need("database_id")}/query", JSONObject().put("page_size", 25), h(c)).check("Notion database").json().optJSONArray("results")
            if (r == null || r.length() == 0) "(no rows)"
            else (0 until r.length()).joinToString("\n") { i ->
                val o = r.getJSONObject(i); val props = o.optJSONObject("properties")
                val extra = props?.keys()?.asSequence()?.mapNotNull { k ->
                    val pr = props.optJSONObject(k) ?: return@mapNotNull null
                    when (pr.optString("type")) {
                        "status" -> "$k=${pr.optJSONObject("status")?.optString("name")}"
                        "select" -> "$k=${pr.optJSONObject("select")?.optString("name")}"
                        "date" -> "$k=${pr.optJSONObject("date")?.optString("start")}"
                        else -> null
                    }
                }?.joinToString(", ").orEmpty()
                "- ${titleOf(o)} | $extra | id=${o.optString("id")}"
            }
        },
        act("page_create", "Create a Notion page under a page or in a database.", listOf(p("parent_id", "page or database id"), p("parent_type", "page or database"), p("title", "title"), p("content", "optional text")), 1) { c, a ->
            val type = a.optString("parent_type", "page").ifBlank { "page" }
            val pid = a.need("parent_id")
            val parent: JSONObject; val props = JSONObject()
            if (type == "database") {
                val db = Http.get("$API/databases/$pid", h(c)).check("Notion database").json().optJSONObject("properties") ?: JSONObject()
                val titleKey = db.keys().asSequence().firstOrNull { db.optJSONObject(it)?.optString("type") == "title" } ?: "Name"
                props.put(titleKey, JSONObject().put("title", richText(a.need("title"))))
                parent = JSONObject().put("database_id", pid)
            } else {
                props.put("title", JSONObject().put("title", richText(a.need("title"))))
                parent = JSONObject().put("page_id", pid)
            }
            val body = JSONObject().put("parent", parent).put("properties", props)
            val content = a.optString("content")
            if (content.isNotBlank()) {
                val blocks = JSONArray()
                content.split("\n").filter { it.isNotBlank() }.take(90).forEach { line ->
                    blocks.put(JSONObject().put("object", "block").put("type", "paragraph").put("paragraph", JSONObject().put("rich_text", richText(line))))
                }
                body.put("children", blocks)
            }
            val r = Http.postJson("$API/pages", body, h(c)).check("Notion create").json()
            if (r.str("id").isEmpty()) throw PluginError("Notion did not return a page id.")
            "Page created: ${r.str("url")}"
        }
    )
    val health: suspend (PluginCtx) -> String = { c -> "Connected as " + Http.get("$API/users/me", h(c)).check("Notion").json().str("name").ifBlank { "integration" } }
}

// ── Slack ────────────────────────────────────────────────────────────────────
internal object SlackActions {
    private const val API = "https://slack.com/api"
    private fun h(c: PluginCtx) = mapOf("Authorization" to "Bearer ${c.field("token")}")
    private fun ok(r: HttpResult, what: String): JSONObject {
        r.check(what)
        val j = r.json()
        if (!j.optBoolean("ok")) throw PluginError("$what failed: ${j.optString("error", "unknown error")}" +
            (if (j.optString("error") == "not_in_channel") " (invite the bot to the channel with /invite)" else ""))
        return j
    }
    private suspend fun channelId(c: PluginCtx, name: String): String {
        val n = name.trim().removePrefix("#")
        if (Regex("^[CGD][A-Z0-9]{8,}$").matches(n)) return n
        val j = ok(Http.get("$API/conversations.list?limit=200&exclude_archived=true&types=public_channel,private_channel", h(c)), "Slack channels")
        val ch = j.optJSONArray("channels") ?: throw PluginError("No channels visible to the bot.")
        for (i in 0 until ch.length()) if (ch.getJSONObject(i).optString("name").equals(n, true)) return ch.getJSONObject(i).optString("id")
        throw PluginError("Channel '$name' was not found (or the bot is not a member).")
    }
    val actions: List<PluginAction> = listOf(
        act("channels_list", "List Slack channels the bot can see.", emptyList(), 0) { c, _ ->
            val ch = ok(Http.get("$API/conversations.list?limit=100&exclude_archived=true&types=public_channel,private_channel", h(c)), "Slack channels").optJSONArray("channels") ?: return@act "No channels."
            (0 until ch.length()).joinToString("\n") { i -> val x = ch.getJSONObject(i); "- #${x.optString("name")} | id=${x.optString("id")} | members ${x.optInt("num_members")}" }
        },
        act("history", "Read recent messages of a channel.", listOf(p("channel", "name or id"), p("limit", "1-30, default 10")), 0) { c, a ->
            val id = channelId(c, a.need("channel"))
            val m = ok(Http.get("$API/conversations.history?channel=$id&limit=${a.optInt("limit", 10).coerceIn(1, 30)}", h(c)), "Slack history").optJSONArray("messages") ?: return@act "No messages."
            (0 until m.length()).joinToString("\n") { i -> val x = m.getJSONObject(i); "- ${x.optString("user")}: ${x.optString("text").take(300)}" }
        },
        act("post_message", "SEND a message to a Slack channel.", listOf(p("channel", "name or id"), p("text", "message text")), 2) { c, a ->
            val id = channelId(c, a.need("channel"))
            val j = ok(Http.postJson("$API/chat.postMessage", JSONObject().put("channel", id).put("text", a.need("text")), h(c)), "Slack send")
            if (j.str("ts").isEmpty()) throw PluginError("Slack did not return a message timestamp, so the send is not confirmed.")
            "Message posted to ${a.optString("channel")} (ts ${j.str("ts")})."
        }
    )
    val health: suspend (PluginCtx) -> String = { c -> val j = ok(Http.get("$API/auth.test", h(c)), "Slack"); "Bot ${j.str("user")} in ${j.str("team")}" }
}
