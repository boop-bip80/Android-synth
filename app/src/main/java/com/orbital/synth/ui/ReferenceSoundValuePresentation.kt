package com.orbital.synth.ui

import com.orbital.synth.core.FilterCutoffMath
import com.orbital.synth.core.ParameterId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Auditable readouts for the ONE running synth. Only cutoff has a genuine
 * transfer function to physical units; the other macro controls are continuous
 * normalized settings, so a percentage is truthful but Hz/dB/Q would be fake.
 */
internal data class ReferenceMacroReadout(val visual: String, val spoken: String)

internal object ReferenceSoundValuePresentation {
    fun format(id: ParameterId, normalized: Float, synthSampleRate: Int): ReferenceMacroReadout {
        val control = ReferenceSoundUiModel.normalized(normalized)
        val percent = (control * 100f).roundToInt()
        if (id == ParameterId.CUTOFF) {
            // DSP is already clipped to Nyquist fraction at this SAME render
            // rate; never show the older, numerically incorrect 40*400^x curve.
            val rate = synthSampleRate.coerceIn(8_000, 192_000)
            val hz = FilterCutoffMath.baseHz(control, rate)
            val short = if (hz >= 1000.0) {
                String.format(Locale.US, "%.1f kHz", hz / 1000.0)
            } else {
                hz.roundToInt().toString() + " Hz"
            }
            return ReferenceMacroReadout(
                visual = short,
                spoken = "base filter cutoff " + hz.roundToInt() +
                    " hertz, normalized " + percent +
                    " percent; live modulation may change the instantaneous cutoff",
            )
        }
        val name = when (id) {
            ParameterId.WAVE -> "wave shape"
            ParameterId.RESONANCE -> "resonance"
            ParameterId.MOVEMENT -> "movement"
            ParameterId.FX_AMOUNT -> "FX amount"
            else -> id.name.lowercase(Locale.US).replace('_', ' ')
        }
        return ReferenceMacroReadout(
            visual = "$percent%",
            spoken = "$name $percent percent of normalized control range",
        )
    }
}
