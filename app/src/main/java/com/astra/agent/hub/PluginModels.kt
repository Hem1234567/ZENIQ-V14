package com.astra.agent.hub

import android.content.Context
import org.json.JSONObject

enum class AuthType(val label: String) { TOKEN("Access token"), OAUTH("OAuth 2.0 sign-in"), WEBHOOK("Webhook / bot token") }

/** How far an integration goes in this build. */
enum class Impl(val label: String) { WORKING("Implemented"), PLANNED("Planned") }

class CredField(val key: String, val label: String, val secret: Boolean = true, val hint: String = "", val optional: Boolean = false)

class OAuthSpec(
    val authUrl: String, val tokenUrl: String, val scopes: List<String>,
    val redirectHost: String = "127.0.0.1", val extraAuth: Map<String, String> = emptyMap(),
    val needsSecret: Boolean = false, val revokeUrl: String = ""
)

class PluginParam(val name: String, val desc: String)

/** One operation a plugin can do. risk: 0 = read, 1 = changes data, 2 = sends / deletes / irreversible. */
class PluginAction(
    val id: String, val desc: String, val params: List<PluginParam>, val risk: Int,
    val run: suspend (PluginCtx, JSONObject) -> String
) {
    val riskLabel get() = when (risk) { 0 -> "read"; 1 -> "write"; else -> "send/delete" }
    fun compact(pluginId: String) = "$pluginId.$id(" + params.joinToString(", ") { it.name } + ") - " + desc
}

class Plugin(
    val id: String, val name: String, val description: String, val category: String, val provider: String,
    val auth: AuthType, val phase: Int, val impl: Impl,
    val fields: List<CredField>, val oauth: OAuthSpec?,
    val actions: List<PluginAction>, val permissions: List<String>, val endpoints: String,
    val dependencies: List<String>, val verification: String, val setupHelp: String,
    val keywords: List<String>, val version: String = "1.0",
    /** Fetches the account name; used to verify a connection and for the health monitor. */
    val health: (suspend (PluginCtx) -> String)? = null
)

class PluginError(msg: String) : Exception(msg)

/** Gives a connector its credentials; OAuth access tokens are refreshed when they are about to expire. */
class PluginCtx(val app: Context, val plugin: Plugin) {
    fun field(key: String): String = Vault.get(app, plugin.id).optString(key, "")
    suspend fun token(): String {
        val v = Vault.get(app, plugin.id)
        if (plugin.auth == AuthType.OAUTH) {
            val exp = v.optLong("expires_at", 0L)
            if (v.optString("access_token").isBlank() || (exp in 1..(System.currentTimeMillis() + 60_000))) {
                OAuth.refresh(app, plugin)
                return Vault.get(app, plugin.id).optString("access_token")
            }
            return v.optString("access_token")
        }
        return v.optString("token")
    }
    suspend fun bearer(): Map<String, String> {
        val t = token()
        if (t.isBlank()) throw PluginError("${plugin.name} is not connected. Open Plugins and connect it first.")
        return mapOf("Authorization" to "Bearer $t")
    }
}

fun HttpResult.check(what: String): HttpResult {
    if (ok) return this
    val hint = when (code) {
        401 -> " (the token is invalid or expired: reconnect the plugin)"
        403 -> " (permission missing: check the token's scopes)"
        404 -> " (not found: check the id or name)"
        429 -> " (rate limited: try again in a minute)"
        -1 -> " (no internet?)"
        else -> ""
    }
    throw PluginError("$what failed, HTTP $code$hint: ${body.take(220)}")
}

fun JSONObject.need(key: String): String = optString(key, "").trim().ifEmpty { throw PluginError("Missing argument '$key'.") }
