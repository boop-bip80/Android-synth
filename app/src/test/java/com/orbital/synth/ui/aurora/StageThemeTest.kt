package com.orbital.synth.ui.aurora

import androidx.compose.ui.graphics.Color
import com.orbital.synth.core.stage.glow.GlowTheme
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StageThemeTest {
    private fun linear(c: Float): Double = c.toDouble().let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
    private fun luminance(c: Color) = 0.2126 * linear(c.red) + 0.7152 * linear(c.green) + 0.0722 * linear(c.blue)
    private fun contrast(a: Color, b: Color): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    @Test fun darkIsTheDefaultAndTheModesCycleInOrder() {
        assertEquals(StageThemeMode.DARK, StageThemeMode.fromName(null))
        assertEquals(StageThemeMode.DARK, StageThemeMode.fromName("nonsense"))
        assertEquals(StageThemeMode.LIGHT, StageThemeMode.fromName("LIGHT"))
        assertEquals(StageThemeMode.LIGHT, StageThemeMode.DARK.next())
        assertEquals(StageThemeMode.SYSTEM, StageThemeMode.LIGHT.next())
        assertEquals(StageThemeMode.DARK, StageThemeMode.SYSTEM.next())
        // Each mode has its own glyph, so the switch always shows what is active.
        assertEquals(3, StageThemeMode.entries.map { it.glyph }.toSet().size)
    }

    @Test fun theStagePreferenceDefaultsToDarkAndRemembersAChoice() {
        val prefs = AuroraPrefs()
        assertEquals(StageThemeMode.DARK, prefs.themeMode)
        prefs.themeMode = StageThemeMode.LIGHT
        assertEquals(StageThemeMode.LIGHT, prefs.themeMode)
        prefs.themeMode = StageThemeMode.SYSTEM
        assertEquals(StageThemeMode.SYSTEM, prefs.themeMode)
    }

    @Test fun theDarkPaletteIsExactlyTheLookTheStageAlwaysHad() {
        val p = DarkStagePalette
        assertEquals(GlowTheme.DARK, p.glowTheme)
        assertFalse(p.isLight)
        assertEquals(Color(0xFF05080F), p.ink)
        assertEquals(Color(0xFF0A1220), p.glass)
        assertEquals(Color(0xFFB9C7DA), p.soft)
        assertEquals(Color(0xFFFFC857), p.gold)
        assertEquals(Color.White, p.text)
        assertEquals(Color(0xFF0D1626), p.sheetTop)
        assertEquals(Color.Black.copy(alpha = .62f), p.scrim)
        // Lane and key hues are shown untouched on black.
        assertEquals(rgbColor(0x34DCFF), p.tint(0x34DCFF))
    }

    @Test fun theLightPaletteIsPaleAndReInksKeyColours() {
        val p = LightStagePalette
        assertEquals(GlowTheme.LIGHT, p.glowTheme)
        assertTrue(p.isLight)
        assertTrue("panels are light", luminance(p.glass) > 0.8 && luminance(p.ink) > 0.7)
        assertTrue("text is dark", luminance(p.text) < 0.05)
        val cyan = rgbColor(0x34DCFF)
        assertNotEquals(cyan, p.tint(0x34DCFF))
        assertTrue("a key colour must be darker on paper", luminance(p.tint(0x34DCFF)) < luminance(cyan) / 2)
        assertTrue("the backdrop is pale", luminance(rgbColor(GlowTheme.LIGHT.backdropTop)) > 0.8)
    }

    @Test fun everyTextColourIsReadableInBothLooks() {
        data class Look(val name: String, val p: StagePalette, val backgrounds: List<Color>)
        val looks = listOf(
            Look("dark", DarkStagePalette, listOf(DarkStagePalette.glass, DarkStagePalette.ink, DarkStagePalette.sheetTop, Color.Black)),
            Look("light", LightStagePalette, listOf(
                LightStagePalette.glass, LightStagePalette.ink, LightStagePalette.sheetTop,
                rgbColor(GlowTheme.LIGHT.backdropTop), rgbColor(GlowTheme.LIGHT.backdropBottom),
            )),
        )
        for (look in looks) for (background in look.backgrounds) {
            val where = "${look.name} on ${background}"
            assertTrue("primary text $where: ${contrast(look.p.text, background)}", contrast(look.p.text, background) >= 7.0)
            assertTrue("secondary text $where: ${contrast(look.p.soft, background)}", contrast(look.p.soft, background) >= 4.5)
            assertTrue("accent $where: ${contrast(look.p.gold, background)}", contrast(look.p.gold, background) >= 4.5)
        }
    }
}
