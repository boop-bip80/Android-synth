package com.orbital.synth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** UI preferences for the clear, keyboardless stage. Never overwrites a synth patch. */
data class StageTouchSettings(
    val tonic: Int = 0,
    /** Octave of the lowest (bass) lane's tonic. 2 puts C2 at the bottom of the screen. */
    val baseOctave: Int = 2,
    val octaveRange: Int = 3,
    val scale: TouchScale = TouchScale.MAJOR,
    val pressureSensitivity: Float = .5f,
    /** Aurora lanes always run bass (bottom) to treble (top); kept for stored-file compatibility. */
    val pitchAxis: TouchPitchAxis = TouchPitchAxis.VERTICAL,
    val glissando: Boolean = true,
    /** OFF by default; Android may decline even an opted-in one-shot accent. */
    val hapticsEnabled: Boolean = false,
) {
    fun toTouchConfig(): TouchPerformanceConfig = TouchPerformanceConfig(
        rootMidiNote = ((baseOctave.coerceIn(0, 8) + 1) * 12 + tonic.coerceIn(0, 11)).coerceIn(0, 127),
        noteCount = (1 + octaveRange.coerceIn(1, 4) * scale.semitones.size).coerceIn(1, 64),
        scale = scale,
        glissando = glissando,
        pressureSensitivity = if (pressureSensitivity.isFinite()) pressureSensitivity.coerceIn(0f, 1f) else .5f,
        pitchAxis = pitchAxis,
    )
}

private val PanelBlack = Color(0xFF05070B)
private val PanelSurface = Color(0xFF0B101A)
private val PanelCyan = Color(0xFF70EEFF)
private val PanelViolet = Color(0xFFB19BFF)
private val PanelMuted = Color(0xFF899AAD)
private val PanelBorder = Color(0xFF284458)
private val Tonics = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
private val Scales = listOf(
    TouchScale.MAJOR to "MAJOR", TouchScale.NATURAL_MINOR to "MINOR",
    TouchScale.DORIAN to "DORIAN", TouchScale.MIXOLYDIAN to "MIXOLYDIAN",
    TouchScale.LYDIAN to "LYDIAN", TouchScale.PHRYGIAN to "PHRYGIAN",
    TouchScale.MAJOR_PENTATONIC to "MAJOR PENT", TouchScale.MINOR_PENTATONIC to "MINOR PENT",
    TouchScale.BLUES to "BLUES", TouchScale.HARMONIC_MINOR to "HARMONIC MINOR",
    TouchScale.HIRAJOSHI to "HIRAJOSHI", TouchScale.WHOLE_TONE to "WHOLE TONE",
    TouchScale.CHROMATIC to "CHROMATIC",
)

/** Dark-glass scrollable stage configuration dialog with >=48dp accessible controls. */
@Composable
internal fun StageTouchConfigurationPanel(
    settings: StageTouchSettings,
    onChange: (StageTouchSettings) -> Unit,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 560.dp).heightIn(max = 620.dp)
                .background(PanelBlack, RoundedCornerShape(24.dp))
                .border(1.dp, PanelCyan.copy(alpha = .36f), RoundedCornerShape(24.dp))
                .verticalScroll(rememberScrollState()).testTag("stageConfigPanel")
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Text("STAGE / CONFIGURATION", color = PanelCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
            Text("Tune the surface to your hands", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("Pick the key on the stage itself; use this panel for range, feel and tactile feedback.", color = PanelMuted, fontSize = 12.sp)

            ConfigSection(
                "TACTILE / OPT IN",
                "Optional short response on a fresh note only. Never on drags; Android and accessibility settings can suppress it.",
            ) {
                ConfigChoiceRow {
                    ConfigChip(
                        "OFF · DEFAULT", !settings.hapticsEnabled,
                        "Disable tactile note accents", "stageHapticsOff",
                    ) {
                        onChange(settings.copy(hapticsEnabled = false))
                    }
                    ConfigChip(
                        "SUBTLE · ON", settings.hapticsEnabled,
                        "Enable one short tactile accent at note onset", "stageHapticsOn",
                    ) {
                        onChange(settings.copy(hapticsEnabled = true))
                    }
                }
            }

            ConfigSection("01 / SCALE", "Every lane is a note of this scale, so nothing can sound wrong.") {
                ConfigChoiceRow {
                    Scales.forEach { (scale, name) ->
                        ConfigChip(name, settings.scale == scale, "Scale $name", "stageScale-" + scale.name) {
                            onChange(settings.copy(scale = scale))
                        }
                    }
                }
            }
            ConfigSection("02 / KEY", "The home note. Its lanes glow brightest.") {
                ConfigChoiceRow {
                    Tonics.forEachIndexed { i, name ->
                        ConfigChip(name, settings.tonic == i, "Tonic $name", "stageTonic-$i") {
                            onChange(settings.copy(tonic = i))
                        }
                    }
                }
            }
            ConfigSection("03 / BASS OCTAVE", "Register of the lowest lane at the bottom of the screen.") {
                ConfigChoiceRow {
                    (0..8).forEach { octave ->
                        ConfigChip("C$octave", settings.baseOctave == octave, "Starting octave $octave", "stageBaseOctave-$octave") {
                            onChange(settings.copy(baseOctave = octave))
                        }
                    }
                }
            }
            ConfigSection("04 / RANGE", "How many octaves fit under your fingers (small screens may show fewer).") {
                ConfigChoiceRow {
                    (1..4).forEach { range ->
                        ConfigChip("$range OCT", settings.octaveRange == range, "$range octave range", "stageOctaveRange-$range") {
                            onChange(settings.copy(octaveRange = range))
                        }
                    }
                }
            }
            ConfigSection("05 / LAYOUT", "Bass sits at the bottom, treble at the top. Slide sideways to brighten the tone.") {
                ConfigChoiceRow {
                    // The stage has one musical layout; the chip keeps the stored setting honest.
                    ConfigChip("BASS ↓  TREBLE ↑", true,
                        "Vertical pitch, horizontal timbre", "stageAxisVertical") {
                        onChange(settings.copy(pitchAxis = TouchPitchAxis.VERTICAL))
                    }
                }
            }
            ConfigSection("06 / NOTE MOTION", "Sweep through notes or sustain the starting pitch.") {
                ConfigChoiceRow {
                    ConfigChip("GLIDE", settings.glissando, "Retune notes as fingers move", "stageMotionGlide") {
                        onChange(settings.copy(glissando = true))
                    }
                    ConfigChip("HOLD", !settings.glissando, "Hold each starting note", "stageMotionHold") {
                        onChange(settings.copy(glissando = false))
                    }
                }
            }
            ConfigSection("07 / PRESSURE", "Optional pressure shapes dynamics; XY and speed always work without sensing.") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("SENSITIVITY", fontSize = 10.sp, color = PanelMuted)
                    Text(((settings.pressureSensitivity.coerceIn(0f, 1f) * 100).toInt()).toString() + "%",
                        color = PanelViolet, fontSize = 11.sp)
                }
                Slider(
                    value = settings.pressureSensitivity.coerceIn(0f, 1f),
                    onValueChange = { onChange(settings.copy(pressureSensitivity = it.coerceIn(0f, 1f))) },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(thumbColor = PanelCyan, activeTrackColor = PanelViolet, inactiveTrackColor = PanelBorder),
                    modifier = Modifier.fillMaxWidth().testTag("stagePressureSensitivity")
                        .semantics { contentDescription = "Optional pressure sensitivity" },
                )
            }
            Text(
                "LIVE MAP  ·  HEIGHT = NOTE     SIDEWAYS = BRIGHTNESS + TOUCH FORCE     SPEED = MOTION",
                color = PanelCyan.copy(alpha = .8f), fontSize = 9.sp, letterSpacing = .6.sp,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ConfigChip("RESET", false, "Reset stage settings", "stageConfigReset", onReset)
                Spacer(Modifier.weight(1f))
                ConfigChip("BACK TO FIELD", true, "Return to performance", "stageConfigClose", onDismiss)
            }
        }
    }
}

@Composable private fun ConfigSection(title: String, helper: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = PanelViolet, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.8.sp)
        Text(helper, color = PanelMuted, fontSize = 11.sp)
        content()
    }
}

@Composable private fun ConfigChoiceRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        content()
    }
}

@Composable private fun ConfigChip(
    text: String, chosen: Boolean, description: String, tag: String, onClick: () -> Unit,
) {
    val corner = RoundedCornerShape(13.dp)
    Column(
        Modifier.heightIn(min = 48.dp)
            .background(if (chosen) Color(0xFF123042) else PanelSurface, corner)
            .border(1.dp, if (chosen) PanelCyan.copy(alpha = .83f) else PanelBorder, corner)
            .clickable(role = Role.Button, onClick = onClick).testTag(tag)
            .semantics { contentDescription = description; selected = chosen; role = Role.Button }
            .padding(horizontal = 13.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold,
            color = if (chosen) PanelCyan else Color(0xFFD2DCE9))
    }
}
