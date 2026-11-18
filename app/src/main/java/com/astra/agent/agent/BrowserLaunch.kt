package com.astra.agent.agent

/** How ZENIQ asks a browser for a NEW tab. Pure data so it can be unit tested; Tools.kt turns it into Intents. */
object BrowserLaunch {
    class Spec(val url: String, val pkg: String?, val extras: Map<String, Any>)

    fun normalize(url: String): String {
        val t = url.trim()
        return if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "https://$t"
    }

    /**
     * Chrome first (create_new_tab plus a unique application id so a tab is never reused), then the default browser with the same extras.
     * Browsers differ, so the last fallback is verified on the device (read_screen / current URL).
     */
    fun candidates(url: String, nonce: Long): List<Spec> {
        val u = normalize(url)
        val extras = mapOf<String, Any>("create_new_tab" to true, "com.android.browser.application_id" to "zeniq.tab.$nonce")
        return listOf(Spec(u, "com.android.chrome", extras), Spec(u, null, extras))
    }
}
