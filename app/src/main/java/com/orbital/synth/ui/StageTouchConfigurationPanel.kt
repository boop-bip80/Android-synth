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
    val baseOctave: Int = 4,
    val octaveRange: Int = 2,
    val scale: TouchScale = TouchScale.CHROMATIC,
    val pressureSensitivity: Float = .5f,
    val pitchAxis: TouchPitchAxis = TouchPitchAxis.HORIZONTAL,
    val glissando: Boolean = true,
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
    TouchScale.CHROMATIC to "CHROMATIC", TouchScale.MAJOR to "MAJOR",
    TouchScale.NATURAL_MINOR to "MINOR", TouchScale.DORIAN to "DORIAN",
    TouchScale.MINOR_PENTATONIC to "PENTATONIC", TouchScale.BLUES to "BLUES",
    TouchScale.WHOLE_TONE to "WHOLE TONE",
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
            Text("FIELD / CONFIGURATION", color = PanelCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
            Text("Compose a space for your hands", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("Choose what every touch plays. The performance screen stays completely clear.", color = PanelMuted, fontSize = 12.sp)

            ConfigSection("01 / SCALE", "Only notes in this scale are played.") {
                ConfigChoiceRow {
                    Scales.forEach { (scale, name) ->
                        ConfigChip(name, settings.scale == scale, "Scale $name", "stageScale-" + scale.name) {
                            onChange(settings.copy(scale = scale))
                        }
                    }
                }
            }
            ConfigSection("02 / TONIC", "Root note at the edge of the screen.") {
                ConfigChoiceRow {
                    Tonics.forEachIndexed { i, name ->
                        ConfigChip(name, settings.tonic == i, "Tonic $name", "stageTonic-$i") {
                            onChange(settings.copy(tonic = i))
                        }
                    }
                }
            }
            ConfigSection("03 / START OCTAVE", "Lowest register of the sound field.") {
                ConfigChoiceRow {
                    (1..7).forEach { octave ->
                        ConfigChip("C$octave", settings.baseOctave == octave, "Starting octave $octave", "stageBaseOctave-$octave") {
                            onChange(settings.copy(baseOctave = octave))
                        }
                    }
                }
            }
            ConfigSection("04 / RANGE", "How many octaves fit under your fingers.") {
                ConfigChoiceRow {
                    (1..4).forEach { range ->
                        ConfigChip("$range OCT", settings.octaveRange == range, "$range octave range", "stageOctaveRange-$range") {
                            onChange(settings.copy(octaveRange = range))
                        }
                    }
                }
            }
            ConfigSection("05 / PITCH AXIS", "Move sideways or vertically to select pitches.") {
                ConfigChoiceRow {
                    ConfigChip("X / LEFT → RIGHT", settings.pitchAxis == TouchPitchAxis.HORIZONTAL,
                        "Horizontal pitch, vertical timbre", "stageAxisHorizontal") {
                        onChange(settings.copy(pitchAxis = TouchPitchAxis.HORIZONTAL))
                    }
                    ConfigChip("Y / DOWN → UP", settings.pitchAxis == TouchPitchAxis.VERTICAL,
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
                if (settings.pitchAxis == TouchPitchAxis.HORIZONTAL)
                    "LIVE MAP  ·  X PITCH / MORPH     Y FILTER     SPEED MOTION"
                else "LIVE MAP  ·  Y PITCH / MORPH     X FILTER     SPEED MOTION",
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
