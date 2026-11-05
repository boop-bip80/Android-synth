package com.orbital.synth.ui.aurora

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.orbital.synth.core.stage.glow.GlowTheme
import com.orbital.synth.core.stage.glow.LightInk

/**
 * How the AURORA stage looks. DARK is the original glowing-on-black look (the default, so
 * nothing changes until the player chooses otherwise); LIGHT is "normal mode": the same living
 * surface drawn as ink on pale paper; SYSTEM follows the phone's own dark-mode setting.
 *
 * FOCUS (the clean canvas) is pure black in every mode, by design.
 */
internal enum class StageThemeMode(val glyph: String, val label: String) {
    DARK("●", "Dark"),
    LIGHT("○", "Normal"),
    SYSTEM("◐", "Auto");

    fun next(): StageThemeMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromName(name: String?): StageThemeMode = entries.firstOrNull { it.name == name } ?: DARK
    }
}

/** Every colour the stage chrome (header, footer, sheets) draws with, for one look. */
internal class StagePalette(
    val glowTheme: GlowTheme,
    /** Panel gradient end (the darker edge in dark mode, the cooler edge in light mode). */
    val ink: Color,
    /** Panel gradient start and pill fill. */
    val glass: Color,
    /** Secondary text. */
    val soft: Color,
    /** Guidance and call-to-action accent. */
    val gold: Color,
    /** Primary text. */
    val text: Color,
    /** Dim layer behind a sheet. */
    val scrim: Color,
    /** Top of a sheet's gradient. */
    val sheetTop: Color,
    /** Faint dividers and progress tracks. */
    val hairline: Color,
    /** Background of the small coaching hint. */
    val hintBackground: Color,
    /** Hint text. */
    val hintText: Color,
) {
    val isLight: Boolean get() = glowTheme == GlowTheme.LIGHT

    /** The colour to show a key/lane hue in: unchanged on black, re-inked on paper. */
    fun tint(rgb: Int): Color = if (isLight) rgbColor(LightInk.ink(rgb)) else rgbColor(rgb)

    val backdrop: Brush
        get() = Brush.verticalGradient(listOf(rgbColor(glowTheme.backdropTop), rgbColor(glowTheme.backdropBottom)))
}

/** Exactly the colours the stage chrome used before themes existed. */
internal val DarkStagePalette = StagePalette(
    glowTheme = GlowTheme.DARK,
    ink = Color(0xFF05080F),
    glass = Color(0xFF0A1220),
    soft = Color(0xFFB9C7DA),
    gold = Color(0xFFFFC857),
    text = Color.White,
    scrim = Color.Black.copy(alpha = .62f),
    sheetTop = Color(0xFF0D1626),
    hairline = Color.White.copy(alpha = .10f),
    hintBackground = Color.Black.copy(alpha = .72f),
    hintText = Color.White.copy(alpha = .7f),
)

internal val LightStagePalette = StagePalette(
    glowTheme = GlowTheme.LIGHT,
    ink = Color(0xFFE8EEF6),
    glass = Color(0xFFFFFFFF),
    soft = Color(0xFF45566D),
    gold = Color(0xFF9A5B00),
    text = Color(0xFF0F1A2E),
    scrim = Color(0xFF0F1A2E).copy(alpha = .38f),
    sheetTop = Color(0xFFFFFFFF),
    hairline = Color(0xFF0F1A2E).copy(alpha = .14f),
    hintBackground = Color.White.copy(alpha = .88f),
    hintText = Color(0xFF0F1A2E).copy(alpha = .78f),
)

@Composable
@ReadOnlyComposable
internal fun stagePaletteFor(mode: StageThemeMode): StagePalette = when (mode) {
    StageThemeMode.DARK -> DarkStagePalette
    StageThemeMode.LIGHT -> LightStagePalette
    StageThemeMode.SYSTEM -> if (isSystemInDarkTheme()) DarkStagePalette else LightStagePalette
}

internal val LocalStagePalette = staticCompositionLocalOf { DarkStagePalette }
