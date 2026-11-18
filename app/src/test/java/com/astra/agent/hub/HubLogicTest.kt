package com.astra.agent.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubLogicTest {
    private fun ids(r: String) = SkillEngine.selectFrom(SkillCatalog.all, r, 5).map { it.skill.id }

    @Test fun skillCatalogHasEverySkillComplete() {
        assertTrue(SkillCatalog.all.size >= 60)
        for (s in SkillCatalog.all) { assertTrue(s.id, s.instructions.length > 30); assertTrue(s.id, s.steps.isNotEmpty()); assertTrue(s.id, s.verification.isNotEmpty()) }
        assertEquals(SkillCatalog.all.size, SkillCatalog.all.map { it.id }.toSet().size)
    }
    @Test fun autoSelectionMatchesTheExamples() {
        assertTrue("powerpoint" in ids("Create a PowerPoint presentation about Artificial Intelligence"))
        assertTrue("debugging" in ids("Find and fix the errors in my Python project"))
        assertTrue("web_form" in ids("Open this website, log in, and complete the form"))
        assertTrue("excel" in ids("Analyze this Excel file and show me the important results"))
        assertTrue(ids("hi how are you").isEmpty())
    }
    @Test fun dependenciesComeAlong() { assertTrue("writing" in ids("make a pptx deck about space")) }

    @Test fun atAutoParsing() {
        assertEquals("open youtube", AutoCommand.parse("@auto open youtube")?.prompt)
        assertEquals("do x", AutoCommand.parse("@auto: do x")?.prompt)
        assertEquals("", AutoCommand.parse("@AUTO")?.prompt)
        assertNull(AutoCommand.parse("@automatic thing")); assertNull(AutoCommand.parse("hello @auto"))
    }

    @Test fun expectedScreenTerms() {
        assertTrue("youtube" in AutoPilot.expectTerms("open YouTube and play lofi music"))
        assertTrue("lofi music" in AutoPilot.expectTerms("open YouTube and play lofi music"))
        assertTrue("settings" in AutoPilot.expectTerms("Open the 'Settings' app"))
    }

    @Test fun planRoundTripAndValidation() {
        val a = Step("s1", "Phone Agent", StepKind.DEVICE, "Do it", emptyList(), instruction = "open youtube")
        val v = Step("verify", "Verification Agent", StepKind.VERIFY, "Verify", listOf("s1"))
        val p = Plan("open youtube", listOf(a, v), emptyList(), emptyList(), emptyList(), false)
        val back = PlanJson.fromString(PlanJson.toJson(p).toString())
        assertNotNull(back); assertEquals(2, back!!.steps.size); assertEquals(StepKind.DEVICE, back.steps[0].kind)
        assertTrue(PlanJson.validate(p).isEmpty())
        a.instruction = ""
        assertTrue(PlanJson.validate(p).isNotEmpty())
        a.instruction = "x"; v.dependsOn = listOf("gone")
        assertTrue(PlanJson.validate(p).any { it.contains("no longer exists") })
    }

    @Test fun oldAutomationCardsStillLoad() {
        val old = """[{"id":"123","name":"Old","req":"do it","h":8,"m":30,"task":"t1"}]"""
        val l = AutomationStore.parseList(old)
        assertEquals(1, l.size); assertEquals("do it", l[0].request); assertEquals(8, l[0].hour); assertEquals("", l[0].planJson); assertEquals(123L, l[0].created)
        val again = AutomationStore.parseList(AutomationStore.toJsonArray(l).toString())
        assertEquals("Old", again[0].name)
    }
}
