package com.orbital.synth.ui

import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.dsp.processing.LfoShape
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin

/** A normalized, static LFO configuration drawing, never the audio worker's phase. */
internal data class GardenPoint(val x: Float, val voltage: Float)
internal data class GardenCable(val slot: Int, val destination: ModDestination, val depth: Float)
internal data class GardenLane(
    val slot: Int,
    val source: ModSource,
    val config: ModulationLfoConfig,
    val rateHz: Float,
    val synchronized: Boolean,
    val connectedCount: Int,
    val reservedCount: Int,
    val liveCables: List<GardenCable>,
    val trace: List<GardenPoint>,
) {
    val maxLiveDepth: Float get() = liveCables.maxOfOrNull { abs(it.depth) } ?: 0f
}

/**
 * Pure editor conversions aligned to TempoLfo: six bipolar shapes, 0.01..40Hz,
 * quarter-note sync at 20..400BPM, and route *cable* depth (not LFO amplitude).
 * SAMPLE_HOLD is explicitly an illustrative two-cycle sketch: actual random
 * samples are private to the audio worker and cannot truthfully be previewed.
 */
internal object LfoConfigurationGardenModel {
    const val TRACE_SAMPLES = 96
    private val sources = arrayOf(
        ModSource.LFO1, ModSource.LFO2, ModSource.LFO3, ModSource.LFO4,
        ModSource.LFO5, ModSource.LFO6, ModSource.LFO7, ModSource.LFO8,
    )

    fun source(slot: Int): ModSource {
        require(slot in 0..7)
        return sources[slot]
    }

    fun effectiveHz(config: ModulationLfoConfig, tempoBpm: Float): Float {
        val rate = if (config.rateHz.isFinite()) config.rateHz.coerceIn(.01f, 40f) else 1f
        val beats = if (config.beatsPerCycle.isFinite()) config.beatsPerCycle else 0f
        return if (beats > 0f) {
            (ModulationConstellationModel.safeTempo(tempoBpm) /
                (60f * beats.coerceIn(.0625f, 64f))).coerceIn(.01f, 40f)
        } else rate
    }

    fun faster(config: ModulationLfoConfig, faster: Boolean): ModulationLfoConfig =
        if (config.beatsPerCycle > 0f && config.beatsPerCycle.isFinite()) {
            config.copy(beatsPerCycle = ModulationConstellationModel.adjacentBeats(
                config.beatsPerCycle, if (faster) -1 else 1,
            ))
        } else {
            val safe = effectiveHz(config, 120f)
            config.copy(rateHz = (safe * if (faster) 2f else .5f).coerceIn(.01f, 40f))
        }

    /** Switch clock families while preserving approximate cycle length. */
    fun toggleSync(config: ModulationLfoConfig, tempoBpm: Float): ModulationLfoConfig {
        val hz = effectiveHz(config, tempoBpm)
        if (config.beatsPerCycle > 0f && config.beatsPerCycle.isFinite()) {
            return config.copy(beatsPerCycle = 0f, rateHz = hz)
        }
        val idealBeats = ModulationConstellationModel.safeTempo(tempoBpm) / (60f * hz)
        val nearest = ModulationConstellationModel.synchronizedBeats.minByOrNull { candidate ->
            abs(ln(candidate.toDouble() / idealBeats.coerceAtLeast(.0625f).toDouble()))
        } ?: 4f
        return config.copy(beatsPerCycle = nearest)
    }

    fun lane(
        slot: Int,
        raw: ModulationLfoConfig,
        routes: List<ModulationRouteConfig>,
        tempoBpm: Float,
    ): GardenLane {
        val source = source(slot)
        val live = ArrayList<GardenCable>()
        var connected = 0
        var reserved = 0
        routes.take(64).forEachIndexed { index, route ->
            if (route.enabled && route.source == source) {
                connected++
                if (ModulationConstellationModel.audibleTarget(route.destination)) {
                    val safeDepth = if (route.depth.isFinite()) route.depth.coerceIn(-4f, 4f) else 0f
                    live += GardenCable(index, route.destination, safeDepth)
                } else reserved++
            }
        }
        val shape = raw.shape
        val trace = List(TRACE_SAMPLES + 1) { index ->
            val period = (index % (TRACE_SAMPLES / 2)).toFloat() / (TRACE_SAMPLES / 2)
            val voltage = when (shape) {
                LfoShape.SINE -> sin(2f * PI.toFloat() * period)
                LfoShape.TRIANGLE -> 1f - 4f * abs(period - .5f)
                LfoShape.SAW_UP -> 2f * period - 1f
                LfoShape.SAW_DOWN -> 1f - 2f * period
                LfoShape.SQUARE -> if (period < .5f) 1f else -1f
                LfoShape.SAMPLE_HOLD -> if (index < TRACE_SAMPLES / 2) -.42f else .66f
            }
            GardenPoint(index.toFloat() / TRACE_SAMPLES, voltage.coerceIn(-1f, 1f))
        }
        return GardenLane(
            slot = slot,
            source = source,
            config = raw,
            rateHz = effectiveHz(raw, tempoBpm),
            synchronized = raw.beatsPerCycle.isFinite() && raw.beatsPerCycle > 0f,
            connectedCount = connected,
            reservedCount = reserved,
            liveCables = live,
            trace = trace,
        )
    }
}
