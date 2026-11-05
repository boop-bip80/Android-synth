package com.orbital.synth.core.jazz

import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import com.orbital.synth.core.preset.SynthPatch
import kotlin.math.roundToInt

/**
 * Builds the default sound of a tune: the tune's [JazzVoice] from [JazzVoices], tailored
 * deterministically by its tempo, feel and mode.
 *
 *  - **Tempo.** Faster tunes get a shorter release (and shorter decay on struck/plucked
 *    voices) so changes stay articulate, and less effect wash. Slow tunes bloom longer.
 *  - **Mode.** Minor-flavoured homes sound slightly darker (lower cutoff, softer morph).
 *  - **Feel.** Ballads gain reverb, up-tempo swing loses some; funk is shortened; modal
 *    tunes breathe a little more.
 *  - **Tempo lock.** The patch stores the tune's BPM as its modulation tempo, so any
 *    beat-synced LFO or STEP clock in the voice follows the song.
 *
 * The result is an ordinary [SynthPatch]: editable, saveable and exportable like any other.
 */
object JazzPresets {
    const val ID_PREFIX = "rb1-"

    fun patchFor(standard: JazzStandard): SynthPatch {
        val base = JazzVoices.patch(standard.voice)
        val pace = ((standard.bpm - 60) / 180f).coerceIn(0f, 1f)
        val family = standard.voice.family
        val parameters = base.parameters.toMutableMap()

        fun tweak(key: String, change: (Float) -> Float) {
            parameters[key] = round2(change(parameters.getValue(key)))
        }

        tweak("RELEASE") { it * (1.15f - 0.45f * pace) }
        if (family == VoiceFamily.KEYS || family == VoiceFamily.PLUCK || family == VoiceFamily.MALLET) {
            tweak("DECAY") { it * (1.08f - 0.25f * pace) }
        }
        tweak("FX_AMOUNT") { it + 0.10f * (1f - pace) - 0.06f * pace }

        when (standard.key?.scaleId) {
            "natural_minor", "dorian", "phrygian", "harmonic_minor", "blues" -> {
                tweak("CUTOFF") { it - 0.04f }
                tweak("MORPH") { it - 0.02f }
            }
        }
        when (standard.feel) {
            JazzFeel.FUNK -> tweak("DECAY") { it * 0.85f }
            JazzFeel.MODAL -> tweak("MOVEMENT") { it + 0.10f }
            else -> Unit
        }

        val fxSlots = tunedFx(base.fxSlots, standard.feel)
        val voice = standard.voice
        return SynthPatch(
            id = ID_PREFIX + standard.id,
            name = standard.title,
            author = "Orbital Jazz",
            description = "${voice.label} for ${standard.title}: ${standard.feel.label.lowercase()}, ${standard.bpm} BPM.",
            tags = base.tags + setOf("realbook", standard.feel.code, voice.code),
            parameters = parameters,
            oscillatorSlots = base.oscillatorSlots,
            modulationRoutes = base.modulationRoutes,
            modulationLfos = base.modulationLfos,
            modulationTempoBpm = standard.bpm.toFloat(),
            fxSlots = fxSlots,
        )
    }

    private fun tunedFx(slots: List<FxModuleConfig>, feel: JazzFeel): List<FxModuleConfig> {
        val delta = when (feel) {
            JazzFeel.BALLAD -> 0.10f
            JazzFeel.UP -> -0.05f
            else -> 0f
        }
        if (delta == 0f) return slots
        val tuned = slots.map { slot ->
            if (slot.type == FxModuleType.REVERB) slot.copy(wet = round2(slot.wet + delta)) else slot
        }
        // A ballad always gets some room, even on a voice that ships dry.
        return if (feel == JazzFeel.BALLAD && tuned.none { it.type == FxModuleType.REVERB } && tuned.size < MAX_FX) {
            tuned + FxModuleConfig(FxModuleType.REVERB, enabled = true, wet = 0.18f, depth = 0.40f, rate = 0.55f, stereo = 0.70f)
        } else {
            tuned
        }
    }

    private const val MAX_FX = 6

    private fun round2(value: Float): Float = ((value.coerceIn(0f, 1f) * 100f).roundToInt() / 100f).coerceIn(0f, 1f)
}
