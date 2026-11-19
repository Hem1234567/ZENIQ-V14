package com.astra.agent.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Tool-call repair: the malformed shapes small local models actually emit. Pure JSON/string work. */
class ToolJsonTest {
    private val known = setOf("open_app", "web_search", "open_url", "youtube_search", "send_sms", "set_alarm")

    private fun parse(s: String) = JSONObject(s!!)

    @Test fun cleanCallPassesThrough() {
        val out = ToolJson.repair("""{"name":"open_app","arguments":{"app":"YouTube"}}""", known)!!
        val o = parse(out)
        assertEquals("open_app", o.getString("name"))
        assertEquals("YouTube", o.getJSONObject("arguments").getString("app"))
    }

    @Test fun proseAroundTheObjectIsDropped() {
        val out = ToolJson.repair("Sure! I will do that.\n{\"name\":\"web_search\",\"arguments\":{\"query\":\"lofi\"}}\nDone.", known)!!
        val o = parse(out)
        assertEquals("web_search", o.getString("name"))
        assertEquals("lofi", o.getJSONObject("arguments").getString("query"))
    }

    @Test fun openAICallShapeIsAccepted() {
        val out = ToolJson.repair("""{"function":{"name":"open_app","arguments":{"app":"Settings"}}}""", known)!!
        val o = parse(out)
        assertEquals("open_app", o.getString("name"))
        assertEquals("Settings", o.getJSONObject("arguments").getString("app"))
    }

    @Test fun missingClosingBracesAreAdded() {
        val out = ToolJson.repair("""{"name":"open_app","arguments":{"app":"YouTube"""", known)!!
        val o = parse(out)
        assertEquals("YouTube", o.getJSONObject("arguments").getString("app"))
    }

    @Test fun truncatedStringIsClosed() {
        val out = ToolJson.repair("""{"name":"web_search","arguments":{"query":"lofi mu""", known)!!
        assertEquals("lofi mu", parse(out).getJSONObject("arguments").getString("query"))
    }

    @Test fun danglingCommaOrColonIsDropped() {
        val out = ToolJson.repair("""{"name":"open_app","arguments":""", known)!!
        val o = parse(out)
        assertEquals("open_app", o.getString("name"))
        assertEquals(0, o.getJSONObject("arguments").length())
    }

    @Test fun argumentsAsAStringAreParsed() {
        val out = ToolJson.repair("""{"name":"set_alarm","arguments":"{\"hour\":7,\"minute\":30}"}""", known)!!
        assertEquals(7, parse(out).getJSONObject("arguments").getInt("hour"))
    }

    @Test fun oldMergedNamesAreMapped() {
        assertEquals("web_search", parse(ToolJson.repair("""{"name":"search_web","arguments":{"query":"x"}}""", known)!!).getString("name"))
        assertEquals("web_search", parse(ToolJson.repair("""{"name":"websearch","arguments":{}}""", known)!!).getString("name"))
        assertEquals("youtube_search", parse(ToolJson.repair("""{"name":"youtube","arguments":{}}""", known)!!).getString("name"))
    }

    @Test fun typoIsRepairedToTheNearestTool() {
        assertEquals("open_app", ToolJson.nearest("open_ap", known))
        assertEquals("open_app", ToolJson.nearest("OpenApp", known))
        assertEquals("open_app", ToolJson.nearest("openapp", known))
    }

    @Test fun unknownNameIsRejected() {
        assertNull(ToolJson.nearest("fly_to_moon", known))
        assertNull(ToolJson.repair("""{"name":"fly_to_moon","arguments":{}}""", known))
    }

    @Test fun bracesInsideStringsAreIgnored() {
        val out = ToolJson.repair("""{"name":"open_url","arguments":{"url":"http://a/{b}"}}""", known)!!
        assertEquals("http://a/{b}", parse(out).getJSONObject("arguments").getString("url"))
    }

    @Test fun noObjectAtAllYieldsNull() {
        assertNull(ToolJson.repair("I cannot help with that.", known))
        assertNull(ToolJson.balance("no braces here"))
    }

    @Test fun distanceIsSane() {
        assertEquals(0, ToolJson.distance("abc", "abc"))
        assertEquals(3, ToolJson.distance("", "abc"))
        assertEquals(1, ToolJson.distance("open_app", "open_ap"))
        assertEquals(3, ToolJson.distance("kitten", "sitting"))
    }

    @Test fun repairedCallsAreAlwaysValidJson() {
        val inputs = listOf(
            """{"name":"open_app","arguments":{"app":"YouTube"}""",
            """{"name":"open_app","arguments":{"app":"YouTub""",
            """{"name":"open_app","arguments":""",
            """func {"name":"open_app","arguments":{"app":"x"} hey"""
        )
        for (raw in inputs) {
            val out = ToolJson.repair(raw, known)
            assertNotNull(raw, out)
            assertNotNull(raw, JSONObject(out!!).getString("name"))
        }
    }
}
