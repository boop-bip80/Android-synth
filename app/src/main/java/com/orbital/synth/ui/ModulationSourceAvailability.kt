package com.orbital.synth.ui

import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import com.orbital.synth.core.dsp.processing.ModulationStepConfig

/**
 * Availability in the CURRENT Android host, not an assertion about the
 * persistent ModSource enum or what an external host may supply in the future.
 *
 * All 24 source IDs remain editable and round-trip through saved patches.
 * Only the eight LFOs and programmed STEP generate internally. Android's stage
 * supplies touch X/Y/pressure while a finger is active and restores neutral on
 * release; the remaining twelve do not currently have host producers.
 */
internal enum class ModulationSourceKind(val caption: String) {
    LFO("AVAILABLE IF ENABLED / INTERNAL LFO"),
    STEP("AVAILABLE IF ENABLED / PROGRAMMED STEP"),
    TOUCH("GESTURE-DRIVEN / TOUCH REQUIRED"),
    UNWIRED("UNWIRED / CONFIG ONLY IN THIS APP"),
}

internal object ModulationSourceAvailability {
    val lfoSources: List<ModSource> = listOf(
        ModSource.LFO1, ModSource.LFO2, ModSource.LFO3, ModSource.LFO4,
        ModSource.LFO5, ModSource.LFO6, ModSource.LFO7, ModSource.LFO8,
    )

    fun kind(source: ModSource): ModulationSourceKind = when {
        source in lfoSources -> ModulationSourceKind.LFO
        source == ModSource.STEP -> ModulationSourceKind.STEP
        source == ModSource.TOUCH_X || source == ModSource.TOUCH_Y ||
            source == ModSource.TOUCH_PRESSURE -> ModulationSourceKind.TOUCH
        else -> ModulationSourceKind.UNWIRED
    }

    fun sourceLabel(source: ModSource): String =
        source.name + " · " + kind(source).caption

    fun pickerLabel(source: ModSource): String =
        source.name + " · " + when (kind(source)) {
            ModulationSourceKind.LFO, ModulationSourceKind.STEP -> "IF ENABLED"
            ModulationSourceKind.TOUCH -> "GESTURE"
            ModulationSourceKind.UNWIRED -> "UNWIRED"
        }

    /** Whether this host has a KNOWN enabled audio-clocked source generator. */
    fun generatorEnabled(
        source: ModSource,
        lfos: List<ModulationLfoConfig>,
        steps: ModulationStepConfig?,
    ): Boolean = when (kind(source)) {
        ModulationSourceKind.LFO -> {
            val index = lfoSources.indexOf(source)
            index >= 0 && lfos.getOrNull(index)?.enabled == true
        }
        ModulationSourceKind.STEP -> steps?.enabled == true
        ModulationSourceKind.TOUCH, ModulationSourceKind.UNWIRED -> false
    }

    /**
     * A purely decorative particle may illustrate an ENABLED periodic
     * connection only: never infer velocity or audio from the screen clock.
     * Both positive and inverted (negative) depths are useful real routes.
     */
    fun showConfiguredPhoton(
        route: ModulationRouteConfig,
        lfos: List<ModulationLfoConfig>,
        steps: ModulationStepConfig?,
    ): Boolean =
        route.enabled && route.depth.isFinite() && route.depth != 0f &&
            ModulationConstellationModel.audibleTarget(route.destination) &&
            generatorEnabled(route.source, lfos, steps)

    /** Honest routing status, without claiming measured PCM or current sound. */
    fun routeStatus(
        route: ModulationRouteConfig,
        lfos: List<ModulationLfoConfig>,
        steps: ModulationStepConfig?,
    ): String = when {
        !ModulationConstellationModel.audibleTarget(route.destination) ->
            "RESERVED DSP TARGET / SAVED CONFIG ONLY"
        !route.enabled -> "BYPASSED / ROUTE SAVED"
        !route.depth.isFinite() || route.depth == 0f ->
            "ZERO DEPTH / NO MODULATION"
        kind(route.source) == ModulationSourceKind.UNWIRED ->
            "UNWIRED SOURCE / CONFIG ONLY IN THIS APP"
        kind(route.source) == ModulationSourceKind.TOUCH ->
            "GESTURE-DRIVEN / TOUCH TO MODULATE"
        !generatorEnabled(route.source, lfos, steps) ->
            "SOURCE PAUSED / ENABLE GENERATOR"
        else -> "DSP GENERATOR CONNECTED / SIGNAL DEPENDENT"
    }

    fun isUnwired(source: ModSource): Boolean =
        kind(source) == ModulationSourceKind.UNWIRED

    fun isDspTarget(destination: ModDestination): Boolean =
        ModulationConstellationModel.audibleTarget(destination)
}
