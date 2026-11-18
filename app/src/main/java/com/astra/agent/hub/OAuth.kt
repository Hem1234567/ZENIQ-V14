package com.astra.agent.hub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OAuth 2.0 authorization-code flow with PKCE for installed apps.
 * ZENIQ ships no client secrets: the user registers their own (free) OAuth client with Google or Microsoft and enters its Client ID once.
 * The browser sign-in returns to a loopback address (127.0.0.1 / localhost) that ZENIQ listens on for a single request.
 */
object OAuth {
    @Volatile private var current: ServerSocket? = null

    /** Stops a sign-in that is waiting for the browser (the Cancel button). */
    fun cancel() { try { current?.close() } catch (_: Exception) {} }

    private fun rand(n: Int): String {
        val b = ByteArray(n); SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun challenge(verifier: String): String =
        Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** Opens the sign-in page and waits (up to 3 minutes) for the redirect. Returns an error text, or null on success. */
    suspend fun authorize(ctx: Context, plugin: Plugin): String? {
        val spec = plugin.oauth ?: return "This plugin does not use OAuth."
        val v = Vault.get(ctx, plugin.id)
        val clientId = v.optString("client_id").trim()
        if (clientId.isEmpty()) return "Enter the OAuth Client ID first."
        val verifier = rand(48); val state = rand(16)
        val server = try { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) } catch (e: Exception) { return "Could not open a local port: ${e.message}" }
        server.soTimeout = 180_000
        current = server
        val port = server.localPort
        val redirect = "http://${spec.redirectHost}:$port"
        val q = linkedMapOf(
            "client_id" to clientId, "redirect_uri" to redirect, "response_type" to "code",
            "scope" to spec.scopes.joinToString(" "), "state" to state,
            "code_challenge" to challenge(verifier), "code_challenge_method" to "S256"
        ).apply { putAll(spec.extraAuth) }
        val url = spec.authUrl + "?" + q.entries.joinToString("&") { Http.enc(it.key) + "=" + Http.enc(it.value) }
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { server.close(); return "Could not open the browser: ${e.message}" }

        val params: Map<String, String>? = run {
            withContext(Dispatchers.IO) {
                try {
                    var result: Map<String, String>? = null
                    while (result == null) {
                        val s = server.accept()
                        s.soTimeout = 5000
                        val line = try { s.getInputStream().bufferedReader().readLine() ?: "" } catch (_: Exception) { "" }
                        val path = line.split(" ").getOrNull(1) ?: ""
                        val query = Uri.parse("http://x$path")
                        val code = query.getQueryParameter("code"); val err = query.getQueryParameter("error")
                        val ok = code != null && query.getQueryParameter("state") == state
                        if (code != null || err != null) {
                            val msg = if (ok) "ZENIQ is connected. You can close this tab and return to the app." else "Sign-in was not completed. You can close this tab."
                            val html = "<html><head><meta name='viewport' content='width=device-width'></head><body style='font-family:sans-serif;padding:32px'><h2>$msg</h2></body></html>"
                            try { s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n$html").toByteArray()) } catch (_: Exception) {}
                            s.close()
                            result = if (ok) mapOf("code" to code!!) else mapOf("error" to (err ?: "state mismatch"))
                        } else { try { s.close() } catch (_: Exception) {} }   // favicon and other stray requests
                    }
                    result
                } catch (_: Exception) { null }
            }
        }
        try { server.close() } catch (_: Exception) {}
        current = null
        if (params == null) return "Sign-in was cancelled or timed out. Try again."
        params["error"]?.let { return "Sign-in failed: $it" }

        val form = Http.form(
            "client_id" to clientId, "grant_type" to "authorization_code", "code" to params["code"]!!,
            "redirect_uri" to redirect, "code_verifier" to verifier
        ) + (v.optString("client_secret").takeIf { it.isNotBlank() }?.let { "&client_secret=" + Http.enc(it) } ?: "")
        val r = Http.postForm(spec.tokenUrl, form)
        if (!r.ok) return "Token request failed (HTTP ${r.code}): ${r.body.take(200)}"
        store(ctx, plugin, r.json())
        return null
    }

    private fun store(ctx: Context, plugin: Plugin, j: JSONObject) {
        val v = Vault.get(ctx, plugin.id)
        v.put("access_token", j.optString("access_token"))
        if (j.optString("refresh_token").isNotBlank()) v.put("refresh_token", j.optString("refresh_token"))
        v.put("expires_at", System.currentTimeMillis() + j.optLong("expires_in", 3600L) * 1000L)
        Vault.put(ctx, plugin.id, v)
    }

    /** Gets a new access token with the stored refresh token. */
    suspend fun refresh(ctx: Context, plugin: Plugin) {
        val spec = plugin.oauth ?: throw PluginError("Not an OAuth plugin.")
        val v = Vault.get(ctx, plugin.id)
        val rt = v.optString("refresh_token")
        if (rt.isBlank()) throw PluginError("${plugin.name} needs to be connected again (no refresh token).")
        val form = Http.form("client_id" to v.optString("client_id"), "grant_type" to "refresh_token", "refresh_token" to rt) +
            (v.optString("client_secret").takeIf { it.isNotBlank() }?.let { "&client_secret=" + Http.enc(it) } ?: "") +
            (if (spec.scopes.isNotEmpty() && plugin.id == "microsoft") "&scope=" + Http.enc(spec.scopes.joinToString(" ")) else "")
        val r = Http.postForm(spec.tokenUrl, form)
        if (!r.ok) throw PluginError("Could not refresh the ${plugin.name} sign-in (HTTP ${r.code}). Connect it again.")
        store(ctx, plugin, r.json())
    }

    /** Best-effort revoke when the user disconnects (Google). */
    suspend fun revoke(ctx: Context, plugin: Plugin) {
        val spec = plugin.oauth ?: return
        if (spec.revokeUrl.isBlank()) return
        val t = Vault.get(ctx, plugin.id).let { it.optString("refresh_token").ifBlank { it.optString("access_token") } }
        if (t.isNotBlank()) try { Http.postForm(spec.revokeUrl, Http.form("token" to t)) } catch (_: Exception) {}
    }
}
