package com.astra.agent.hub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class HttpResult(val code: Int, val body: String) {
    val ok get() = code in 200..299
    fun json(): JSONObject = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
    fun array(): JSONArray = try { JSONArray(body) } catch (_: Exception) { JSONArray() }
}

/** Minimal HTTPS client for the plugin connectors (no extra libraries). */
object Http {
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun form(vararg kv: Pair<String, String>): String = kv.joinToString("&") { enc(it.first) + "=" + enc(it.second) }

    suspend fun call(
        method: String, url: String, headers: Map<String, String> = emptyMap(),
        body: String? = null, contentType: String = "application/json", timeoutMs: Int = 25_000
    ): HttpResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs; conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "ZENIQ-Android")
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            HttpResult(code, text.take(400_000))
        } catch (e: Exception) {
            HttpResult(-1, "Network error: ${e.message}")
        } finally { conn?.disconnect() }
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()) = call("GET", url, headers)
    suspend fun postJson(url: String, json: JSONObject, headers: Map<String, String> = emptyMap()) = call("POST", url, headers, json.toString())
    suspend fun postForm(url: String, form: String, headers: Map<String, String> = emptyMap()) =
        call("POST", url, headers, form, "application/x-www-form-urlencoded")
}

fun JSONObject.str(key: String): String = if (has(key) && !isNull(key)) optString(key, "") else ""
