package com.astra.agent.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

class ThemeTest {
    private fun lum(c: Color): Double {
        fun f(v: Float): Double { val x = v.toDouble(); return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * f(c.red) + 0.7152 * f(c.green) + 0.0722 * f(c.blue)
    }
    private fun contrast(a: Color, b: Color): Double { val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending(); return (hi + 0.05) / (lo + 0.05) }
    private fun gray(c: Color) = c.red == c.green && c.green == c.blue

    private fun values(c: IosColors) = mapOf("bg" to c.bg, "card" to c.card, "fill" to c.fill, "text" to c.text, "sub" to c.sub, "line" to c.line,
        "accent" to c.accent, "onAccent" to c.onAccent, "green" to c.green, "red" to c.red, "orange" to c.orange)

    @Test fun everyColourIsNeutralGray() {
        for ((name, theme) in listOf("dark" to DarkIos, "light" to LightIos))
            for ((k, v) in values(theme)) assertTrue("$name.$k is not a neutral gray", gray(v))
    }

    @Test fun textContrastIsAtLeast4_5() {
        for ((name, t) in listOf("dark" to DarkIos, "light" to LightIos)) {
            for (bg in listOf(t.bg, t.card, t.fill)) {
                assertTrue("$name text", contrast(t.text, bg) >= 4.5)
                assertTrue("$name secondary text", contrast(t.sub, bg) >= 4.5)
            }
            assertTrue("$name button text on button fill", contrast(t.onAccent, t.accent) >= 4.5)
            assertTrue("$name danger on background", contrast(t.danger, t.bg) >= 4.5)
        }
    }

    @Test fun accentFlipsBetweenThemes() {
        assertEquals(Color.White, DarkIos.accent); assertEquals(Color.Black, LightIos.accent)
    }

    /** No coloured Color(0xFF...) may appear in ui/ (Theme.kt holds the palette; the red constants are the one exception). */
    @Test fun noColouredHardCodedColoursInUi() {
        val dir = File("src/main/java/com/astra/agent/ui")
        assertTrue("ui directory not found from ${File(".").absolutePath}", dir.isDirectory)
        val re = Regex("Color\\(0x([0-9A-Fa-f]{2})([0-9A-Fa-f]{2})([0-9A-Fa-f]{2})([0-9A-Fa-f]{2})\\)")
        val bad = mutableListOf<String>()
        dir.listFiles { f -> f.extension == "kt" }!!.forEach { f ->
            if (f.name == "Theme.kt") return@forEach
            re.findAll(f.readText()).forEach { m -> if (!(m.groupValues[2] == m.groupValues[3] && m.groupValues[3] == m.groupValues[4])) bad += "${f.name}: ${m.value}" }
        }
        assertTrue("coloured hard-coded values: $bad", bad.isEmpty())
    }
}
