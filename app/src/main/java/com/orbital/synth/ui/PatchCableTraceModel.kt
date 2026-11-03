package com.orbital.synth.ui

import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.dsp.processing.ModCurve
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import com.orbital.synth.core.dsp.processing.ModulationStepConfig
import java.util.Locale
import kotlin.math.abs

/**
 * A truthful snapshot of one user-owned modulation cable. Color and glow
 * describe a saved route, its signed depth, and source availability; they are
 * NOT FFT, loudness, measured modulation voltage, or imagined audio activity.
 */
internal enum class PatchCableTraceStatus(val caption: String) {
    PATCHED("PATCHED / SIGNAL DEPENDENT"),
    BYPASSED("BYPASSED"),
    RESERVED("RESERVED / NO AUDIO"),
    ZERO_DEPTH("ZERO DEPTH / NO AUDIO"),
    DORMANT_SOURCE("DORMANT SOURCE"),
}

internal enum class PatchCablePolarity(val caption: String) {
    POSITIVE("POSITIVE"),
    INVERTED("INVERTED"),
    ZERO("ZERO"),
}

internal data class PatchCableTrace(
    val slot: Int,
    val source: ModSource,
    val destination: ModDestination,
    val curve: ModCurve,
    val depth: Float,
    val polarity: PatchCablePolarity,
    val status: PatchCableTraceStatus,
) {
    val number: String get() = (slot + 1).toString().padStart(2, '0')

    /** Explicit sign: a reverse cable is never visually described as positive. */
    val depthCaption: String
        get() = String.format(Locale.US, "%+.2f×", depth)

    val strength: Float get() = (abs(depth) / 4f).coerceIn(0f, 1f)

    val accessibleLabel: String
        get() = "Edit route ${slot + 1}, ${source.name} to ${destination.name}, " +
            "depth ${depthCaption}, ${polarity.caption.lowercase(Locale.US)} polarity, " +
            "${curve.name} curve, ${status.caption.lowercase(Locale.US)}."
}

internal object PatchCableTraceModel {
    fun describe(
        slot: Int,
        route: ModulationRouteConfig,
        lfos: List<ModulationLfoConfig>,
        stepConfig: ModulationStepConfig?,
    ): PatchCableTrace {
        require(slot in 0 until 64) { "route slot must be 0..63" }
        val depth = PatchGraphUiModel.safeDepth(route.depth)
        val status = when {
            route.destination !in PatchGraphUiModel.liveTargets ->
                PatchCableTraceStatus.RESERVED
            !route.enabled -> PatchCableTraceStatus.BYPASSED
            depth == 0f -> PatchCableTraceStatus.ZERO_DEPTH
            route.source == ModSource.STEP && stepConfig?.enabled != true ->
                PatchCableTraceStatus.DORMANT_SOURCE
            PatchGraphUiModel.lfoIndex(route.source) >= 0 &&
                lfos.getOrNull(PatchGraphUiModel.lfoIndex(route.source))?.enabled != true ->
                PatchCableTraceStatus.DORMANT_SOURCE
            else -> PatchCableTraceStatus.PATCHED
        }
        val polarity = when {
            depth > 0f -> PatchCablePolarity.POSITIVE
            depth < 0f -> PatchCablePolarity.INVERTED
            else -> PatchCablePolarity.ZERO
        }
        return PatchCableTrace(slot, route.source, route.destination, route.curve,
            depth, polarity, status)
    }
}
