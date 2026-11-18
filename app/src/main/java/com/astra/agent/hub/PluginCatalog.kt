package com.astra.agent.hub

import org.json.JSONObject

/** The plugin roadmap. Phase 1 and 2 have real connectors; Phase 3 entries are shown as Planned and cannot be connected yet. */
object PluginCatalog {
    private val google = Plugin(
        "google", "Google Workspace", "Gmail, Calendar, Drive, Docs, Sheets and Slides.", "Productivity", "Google",
        AuthType.OAUTH, 1, Impl.WORKING,
        listOf(CredField("client_id", "OAuth Client ID", false, "Desktop-app client from Google Cloud Console"),
            CredField("client_secret", "Client secret", true, "Shown next to the Client ID (Desktop app type)")),
        OAuthSpec("https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
            listOf("openid", "email", "https://www.googleapis.com/auth/gmail.readonly", "https://www.googleapis.com/auth/gmail.compose",
                "https://www.googleapis.com/auth/gmail.send", "https://www.googleapis.com/auth/calendar.events",
                "https://www.googleapis.com/auth/drive.readonly", "https://www.googleapis.com/auth/drive.file",
                "https://www.googleapis.com/auth/spreadsheets", "https://www.googleapis.com/auth/documents",
                "https://www.googleapis.com/auth/presentations"),
            "127.0.0.1", mapOf("access_type" to "offline", "prompt" to "consent"), true, "https://oauth2.googleapis.com/revoke"),
        GoogleActions.actions,
        listOf("Read Gmail", "Create drafts / send email (asks first)", "Read and create Calendar events", "Read Drive files", "Read and append Sheets", "Create Docs and Slides"),
        "gmail.googleapis.com, www.googleapis.com/calendar, drive, sheets, docs, slides", listOf("Internet"),
        "Each write is read back (draft, event) or confirmed by an id returned by Google.",
        "1) Google Cloud Console > create a project and enable the Gmail, Calendar, Drive, Sheets, Docs and Slides APIs.\n" +
            "2) APIs & Services > Credentials > Create OAuth client ID > Application type: Desktop app.\n" +
            "3) Add yourself as a test user on the OAuth consent screen.\n4) Paste the Client ID and secret here and tap Sign in.",
        listOf("gmail", "google calendar", "google drive", "google docs", "google sheets", "google slides", "email", "inbox", "calendar", "drive", "sheet", "meeting"),
        health = { c -> "Signed in as " + Http.get("https://www.googleapis.com/oauth2/v3/userinfo", c.bearer()).check("Google").json().str("email") }
    )

    private val github = Plugin(
        "github", "GitHub", "Repositories, commits, issues and pull requests.", "Developer", "GitHub",
        AuthType.TOKEN, 1, Impl.WORKING,
        listOf(CredField("token", "Personal access token", true, "github.com > Settings > Developer settings > Personal access tokens (repo, read:user)")), null,
        GitHubActions.actions, listOf("Read repositories", "Read and create issues", "Comment on issues and pull requests"),
        "api.github.com", listOf("Internet"), "Creations return the issue number and URL from GitHub.",
        "Create a fine-grained or classic personal access token with repository access (Issues: read/write) and paste it here.",
        listOf("github", "repo", "repository", "commit", "pull request", "issue", "git"), health = GitHubActions.health
    )

    private val microsoft = Plugin(
        "microsoft", "Microsoft 365", "Outlook mail and calendar, OneDrive, Excel and Teams.", "Productivity", "Microsoft",
        AuthType.OAUTH, 1, Impl.WORKING,
        listOf(CredField("client_id", "Application (client) ID", false, "Azure portal > App registrations"),
            CredField("client_secret", "Client secret (leave empty for a public client)", true, "", true)),
        OAuthSpec("https://login.microsoftonline.com/common/oauth2/v2.0/authorize", "https://login.microsoftonline.com/common/oauth2/v2.0/token",
            listOf("User.Read", "Mail.ReadWrite", "Mail.Send", "Calendars.ReadWrite", "Files.ReadWrite", "Chat.ReadWrite", "offline_access"), "localhost"),
        MicrosoftActions.actions,
        listOf("Read Outlook mail", "Send mail (asks first)", "Read and create calendar events", "Read OneDrive files and Excel ranges", "Read and send Teams chat messages (asks first)"),
        "graph.microsoft.com/v1.0", listOf("Internet"), "Events are read back after creation; sends rely on the Graph acceptance response.",
        "1) Azure portal > App registrations > New registration (personal + work accounts).\n2) Authentication > add platform 'Mobile and desktop' with redirect http://localhost and allow public client flows.\n" +
            "3) API permissions: User.Read, Mail.ReadWrite, Mail.Send, Calendars.ReadWrite, Files.ReadWrite, Chat.ReadWrite.\n4) Paste the Application ID here and tap Sign in.",
        listOf("outlook", "microsoft", "onedrive", "teams", "office 365", "m365", "word online", "excel online"),
        health = { c -> "Signed in as " + Http.get("https://graph.microsoft.com/v1.0/me", c.bearer()).check("Microsoft").json().let { it.str("displayName").ifBlank { it.str("userPrincipalName") } } }
    )

    private val notion = Plugin(
        "notion", "Notion", "Search, read and create pages and database rows.", "Productivity", "Notion",
        AuthType.TOKEN, 1, Impl.WORKING,
        listOf(CredField("token", "Internal integration secret", true, "notion.so/my-integrations")), null,
        NotionActions.actions, listOf("Search shared pages", "Read pages and databases", "Create pages"),
        "api.notion.com/v1", listOf("Internet"), "Created pages return a Notion URL.",
        "Create an internal integration at notion.so/my-integrations, copy its secret, then share the pages you want ZENIQ to see with the integration (... > Connections).",
        listOf("notion", "notes database", "project tasks", "wiki"), health = NotionActions.health
    )

    private val slack = Plugin(
        "slack", "Slack", "Read channels and post messages.", "Communication", "Slack",
        AuthType.TOKEN, 1, Impl.WORKING,
        listOf(CredField("token", "Bot token (xoxb-...)", true, "api.slack.com/apps > OAuth & Permissions")), null,
        SlackActions.actions, listOf("List channels", "Read channel history", "Post messages (asks first)"),
        "slack.com/api", listOf("Internet"), "Posts return a message timestamp from Slack.",
        "Create a Slack app, add bot scopes channels:read, channels:history, groups:read, chat:write, install it to your workspace, and paste the Bot User OAuth Token. Invite the bot to the channels it should use.",
        listOf("slack", "channel", "team chat"), health = SlackActions.health
    )

    private val trello = Plugin(
        "trello", "Trello", "Boards, lists and cards.", "Productivity", "Atlassian",
        AuthType.TOKEN, 2, Impl.WORKING,
        listOf(CredField("key", "API key", false, "trello.com/app-key"), CredField("token", "API token", true, "Generate on the same page")), null,
        TrelloActions.actions, listOf("Read boards and cards", "Create cards"), "api.trello.com/1", listOf("Internet"), "Created cards return a URL.",
        "Open trello.com/app-key, copy the API key, then use the Token link on that page to generate a token.",
        listOf("trello", "board", "kanban", "card"), health = TrelloActions.health
    )

    private val jira = Plugin(
        "jira", "Jira", "Search and create Jira Cloud issues.", "Developer", "Atlassian",
        AuthType.TOKEN, 2, Impl.WORKING,
        listOf(CredField("domain", "Site (yourteam.atlassian.net)", false), CredField("email", "Account email", false), CredField("token", "API token", true, "id.atlassian.com > Security > API tokens")), null,
        JiraActions.actions, listOf("Search issues", "Create issues"), "<site>.atlassian.net/rest/api/3", listOf("Internet"), "Created issues return their key.",
        "Enter your Jira Cloud site, account email and an API token from id.atlassian.com.",
        listOf("jira", "ticket", "sprint", "backlog"), health = JiraActions.health
    )

    private val dropbox = Plugin(
        "dropbox", "Dropbox", "List, search, read and upload text files.", "Storage", "Dropbox",
        AuthType.TOKEN, 2, Impl.WORKING,
        listOf(CredField("token", "Access token", true, "dropbox.com/developers/apps > Generate access token")), null,
        DropboxActions.actions, listOf("List and search files", "Read text files", "Upload text files"), "api.dropboxapi.com/2", listOf("Internet"), "Uploads return the stored path and size.",
        "Create a Dropbox app (scoped access), enable files.metadata.read, files.content.read and files.content.write, then generate an access token.",
        listOf("dropbox"), health = DropboxActions.health
    )

    private val discord = Plugin(
        "discord", "Discord", "Read channels and send messages with a bot or webhook.", "Communication", "Discord",
        AuthType.WEBHOOK, 2, Impl.WORKING,
        listOf(CredField("token", "Bot token", true, "", true), CredField("webhook", "Webhook URL", true, "", true)), null,
        DiscordActions.actions, listOf("Read channel messages (bot)", "Send messages (asks first)"), "discord.com/api/v10", listOf("Internet"), "Sends return a message id.",
        "Enter a bot token (developer portal) and/or a channel webhook URL (Channel settings > Integrations > Webhooks).",
        listOf("discord"), health = DiscordActions.health
    )

    private val telegram = Plugin(
        "telegram", "Telegram", "Read bot messages and send messages.", "Communication", "Telegram",
        AuthType.TOKEN, 2, Impl.WORKING,
        listOf(CredField("token", "Bot token", true, "Create with @BotFather")), null,
        TelegramActions.actions, listOf("Read messages sent to the bot", "Send messages (asks first)"), "api.telegram.org", listOf("Internet"), "Sends return a message id.",
        "Talk to @BotFather, create a bot, paste its token here, then send your bot a message so it can see your chat id.",
        listOf("telegram"), health = TelegramActions.health
    )

    private fun planned(id: String, name: String, desc: String, cat: String, prov: String, kw: String) = Plugin(
        id, name, desc, cat, prov, AuthType.OAUTH, 3, Impl.PLANNED, emptyList(), null, emptyList(), emptyList(), "", emptyList(),
        "Not implemented yet.", "Planned for a later version.", kw.split(",").map { it.trim() }
    )

    val all: List<Plugin> = listOf(
        google, github, microsoft, notion, slack, trello, jira, dropbox, discord, telegram,
        planned("zoom", "Zoom", "Meetings and recordings.", "Communication", "Zoom", "zoom"),
        planned("meet", "Google Meet", "Create and join meetings.", "Communication", "Google", "google meet"),
        planned("figma", "Figma", "Read design files and comments.", "Design", "Figma", "figma"),
        planned("canva", "Canva", "Designs and brand kits.", "Design", "Canva", "canva"),
        planned("stripe", "Stripe", "Payments and invoices.", "Business", "Stripe", "stripe"),
        planned("shopify", "Shopify", "Orders and products.", "Business", "Shopify", "shopify"),
        planned("salesforce", "Salesforce", "CRM records.", "Business", "Salesforce", "salesforce"),
        planned("hubspot", "HubSpot", "CRM contacts and deals.", "Business", "HubSpot", "hubspot"),
        planned("maps", "Google Maps & Places", "Places and directions.", "Location", "Google", "google places"),
        planned("storage", "More cloud storage", "Box, iCloud, S3 and others.", "Storage", "Various", "box drive,s3")
    )
}

/** A user-defined plugin: any REST API with a base URL and a token header. */
class CustomPluginDef(val id: String, val name: String, val baseUrl: String, val headerName: String, val headerPrefix: String)

fun customPluginOf(d: CustomPluginDef): Plugin = Plugin(
    d.id, d.name, "Custom REST API at ${d.baseUrl}", "Custom", "You", AuthType.TOKEN, 3, Impl.WORKING,
    listOf(CredField("token", "API token / key", true)), null,
    listOf(act("request", "Call the API: method GET/POST/PUT/PATCH/DELETE, a path starting with /, and an optional JSON body.",
        listOf(p("method", "GET, POST, PUT, PATCH or DELETE"), p("path", "e.g. /v1/items"), p("body", "optional JSON text")), 1) { c, a ->
        val method = a.optString("method", "GET").uppercase().ifBlank { "GET" }
        if (method != "GET") {
            // writes through a custom API are treated as changes; deletes as irreversible (the Hub asks before them)
        }
        val path = a.optString("path").let { if (it.startsWith("/")) it else "/$it" }
        val headers = mapOf(d.headerName to (d.headerPrefix + c.field("token")))
        val r = Http.call(method, d.baseUrl.trimEnd('/') + path, headers, a.optString("body").ifBlank { null }).check("Request")
        "HTTP ${r.code}\n" + r.body.take(8000)
    }),
    listOf("Calls only the base URL you entered"), d.baseUrl, listOf("Internet"), "The HTTP status code of each request is reported.",
    "Custom plugin: enter the API token. ZENIQ sends it in the ${d.headerName} header.", listOf(d.name.lowercase())
)
