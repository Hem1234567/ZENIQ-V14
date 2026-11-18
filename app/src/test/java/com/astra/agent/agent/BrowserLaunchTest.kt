package com.astra.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLaunchTest {
    @Test fun addsHttps() { assertEquals("https://youtube.com", BrowserLaunch.normalize("youtube.com")); assertEquals("http://a.b", BrowserLaunch.normalize(" http://a.b ")) }
    @Test fun chromeFirstWithNewTabExtra() {
        val c = BrowserLaunch.candidates("youtube.com", 1L)
        assertEquals("com.android.chrome", c[0].pkg); assertEquals(true, c[0].extras["create_new_tab"]); assertEquals(null, c[1].pkg)
    }
    @Test fun everyLaunchGetsAUniqueTabId() {
        assertNotEquals(BrowserLaunch.candidates("a.com", 1L)[0].extras["com.android.browser.application_id"], BrowserLaunch.candidates("a.com", 2L)[0].extras["com.android.browser.application_id"])
    }
}
