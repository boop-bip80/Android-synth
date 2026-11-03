package com.orbital.synth.core.dsp.processing

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Independent stereo processors, which may be repeated or reordered in a chain.
 *
 * SATURATION: depth=drive.
 * TREMOLO: depth=amplitude modulation, rate=0.05..12.85 Hz, stereo=0..180-degree offset.
 * CHORUS: depth=delay modulation, rate=0.05..6.05 Hz, stereo=stereo width.
 * DELAY: depth=feedback (maximum 0.88), rate=25..1175 ms, stereo=ping-pong.
 *
 * All level controls are normalized 0..1; [wet] is dry/wet at the module boundary.
 * This is an original effects *chain*, not a replacement for the existing master FX.
 */
enum class FxModuleType { SATURATION, TREMOLO, CHORUS, DELAY }

data class FxModuleConfig(
    val type: FxModuleType = FxModuleType.SATURATION,
    val enabled: Boolean = false,
    val wet: Float = 0f,
    val depth: Float = 0.5f,
    val rate: Float = 0.5f,
    val stereo: Float = 0.5f,
)

/**
 * Allocation-free six-slot stereo effects chain.
 *
 * A single audio render thread exclusively owns this class. From a UI/MIDI thread,
 * publish immutable [FxModuleConfig] snapshots and apply them with [setSlot] only
 * at an audio block boundary. [process], [processInterleaved], [setSlot], [moveSlot]
 * and [reset] must never run concurrently.
 *
 * Every slot preallocates its own chorus/delay histories. Multiple instances of
 * the same effect are genuine independent processors, not multiple labels for
 * one shared delay line. Reordering preserves effect history; crossfade/reorder
 * only when audio is stopped if glitch-free topology mutation is required.
 *
 * Live parameter moves are smoothed by the processor or by an 8 ms per-frame
 * depth slew. A TYPE switch fades the old effect fully dry over 10 ms before
 * introducing the new one, so the old topology cannot leak or jump abruptly.
 * Disable/bypass also fades to dry, then discards old delay histories.
 *
 * With all slots bypassed, PCM in [-1,1] passes through bit-for-bit unchanged.
 * Nonfinite inputs are treated as zero and output is always finite, [-1,1].
 */
class ModularFxChain(
    val sampleRate: Int = 48_000,
    val slotCount: Int = 6,
) {
    init {
        require(sampleRate in 8_000..192_000)
        require(slotCount in 1..8)
    }

    private val stages = Array(slotCount) { FxStage(sampleRate) }
    private val scratch = FloatArray(2)

    fun setSlot(index: Int, config: FxModuleConfig) {
        require(index in 0 until slotCount)
        if (!config.wet.isFinite() || !config.depth.isFinite() ||
            !config.rate.isFinite() || !config.stereo.isFinite()) return
        val safe = config.copy(
            wet = config.wet.coerceIn(0f, 1f),
            depth = config.depth.coerceIn(0f, 1f),
            rate = config.rate.coerceIn(0f, 1f),
            stereo = config.stereo.coerceIn(0f, 1f),
        )
        stages[index].configure(safe)
    }

    /** Last requested (validated) settings; audio state may still be fading. */
    fun slot(index: Int): FxModuleConfig {
        require(index in 0 until slotCount)
        return stages[index].requested
    }

    /** Move a complete processor including its state, without allocating buffers. */
    fun moveSlot(from: Int, to: Int) {
        require(from in 0 until slotCount && to in 0 until slotCount)
        if (from == to) return
        val moving = stages[from]
        if (from < to) {
            for (index in from until to) stages[index] = stages[index + 1]
        } else {
            for (index in from downTo to + 1) stages[index] = stages[index - 1]
        }
        stages[to] = moving
    }

    fun clearAll() {
        for (stage in stages) stage.reset()
    }

    /**
     * Process one stereo frame. In-place aliases of [output] are safe because
     * input scalars are read before any stage writes its output.
     */
    fun process(left: Float, right: Float, output: FloatArray, offset: Int = 0) {
        require(offset >= 0 && offset < output.size - 1)
        var l = sanitize(left)
        var r = sanitize(right)
        for (stage in stages) {
            stage.process(l, r, scratch)
            l = scratch[0]
            r = scratch[1]
        }
        output[offset] = l
        output[offset + 1] = r
    }

    /** Existing interleaved stereo float buffer, in-place; no temporary arrays. */
    fun processInterleaved(buffer: FloatArray, frames: Int) {
        require(frames >= 0 && frames <= buffer.size / 2)
        for (frame in 0 until frames) {
            val offset = frame * 2
            process(buffer[offset], buffer[offset + 1], buffer, offset)
        }
    }

    private fun sanitize(value: Float): Float =
        (if (value.isFinite()) value else 0f).coerceIn(-1f, 1f)

    private class FxStage(sampleRate: Int) {
        var requested = FxModuleConfig()
            private set
        private var active = requested
        private val chorus = StereoChorus(sampleRate).apply { resetForBypass() }
        private val delay = StereoDelay(sampleRate, maxDelaySeconds = 1.25f).apply {
            resetForBypass()
        }
        private val wetOutput = FloatArray(2)
        private val transitionFrames = (sampleRate * 0.010).toInt().coerceAtLeast(1)
        private val depthSlew = (1.0 - exp(-1.0 / (sampleRate * 0.008))).toFloat()
        private var currentDepth = 0.5f
        private var wet = 0f
        private var targetWet = 0f
        private var wetStep = 0f
        private var remainingFrames = 0
        private var pendingTypeSwitch = false
        private var historyCleared = true

        // Recursive quadrature oscillator: no trig operations in a sample callback.
        private var oscSin = 0.0
        private var oscCos = 1.0
        private var stepSin = 0.0
        private var stepCos = 1.0
        private var rightOffsetSin = 0.0
        private var rightOffsetCos = 1.0
        private var renormalizeIn = 4_096

        fun configure(config: FxModuleConfig) {
            if (config == requested && !pendingTypeSwitch) return
            requested = config
            if (config.type != active.type && wet > 0f) {
                pendingTypeSwitch = true
                setWetTarget(0f)
                return
            }
            pendingTypeSwitch = false
            applyActive(config, resetNewType = config.type != active.type)
            setWetTarget(if (config.enabled) config.wet else 0f)
        }

        private fun applyActive(config: FxModuleConfig, resetNewType: Boolean) {
            active = config
            if (resetNewType) {
                chorus.resetForBypass()
                delay.resetForBypass()
                currentDepth = config.depth
            }
            // These are block-rate configuration calls and may use trig/pow.
            when (config.type) {
                FxModuleType.CHORUS -> chorus.configure(
                    rateHz = 0.05f + config.rate * 6.0f,
                    depth = config.depth,
                    wet = 1f,
                    stereoWidth = config.stereo,
                )
                FxModuleType.DELAY -> delay.configure(
                    timeSeconds = 0.025f + config.rate * 1.15f,
                    feedback = config.depth * 0.88f,
                    wet = 1f,
                    pingPong = config.stereo,
                )
                FxModuleType.TREMOLO -> configureTremolo(config.rate, config.stereo)
                FxModuleType.SATURATION -> Unit
            }
        }

        private fun configureTremolo(rateControl: Float, stereo: Float) {
            val hz = 0.05 * 2.0.pow(rateControl.toDouble() * 8.0)
            val radians = 2.0 * PI * hz / sampleRate
            stepSin = sin(radians)
            stepCos = cos(radians)
            val phase = stereo.toDouble() * PI
            rightOffsetSin = sin(phase)
            rightOffsetCos = cos(phase)
        }

        private val sampleRate = sampleRate.toDouble()

        private fun setWetTarget(next: Float) {
            if (next == targetWet) return
            targetWet = next
            if (next == wet) {
                wetStep = 0f
                remainingFrames = 0
            } else {
                remainingFrames = transitionFrames
                wetStep = (next - wet) / transitionFrames
            }
        }

        private fun advanceWet() {
            if (remainingFrames <= 0) return
            wet = (wet + wetStep).coerceIn(0f, 1f)
            remainingFrames--
            if (remainingFrames == 0) wet = targetWet
        }

        fun process(left: Float, right: Float, output: FloatArray) {
            if (pendingTypeSwitch && wet == 0f && remainingFrames == 0) {
                pendingTypeSwitch = false
                applyActive(requested, resetNewType = true)
                setWetTarget(if (requested.enabled) requested.wet else 0f)
            }

            if (wet == 0f && targetWet == 0f) {
                if (!historyCleared) {
                    chorus.resetForBypass()
                    delay.resetForBypass()
                    historyCleared = true
                }
                output[0] = left
                output[1] = right
                return
            }
            advanceWet()
            if (wet == 0f) {
                output[0] = left
                output[1] = right
                return
            }
            historyCleared = false
            currentDepth += (active.depth - currentDepth) * depthSlew
            when (active.type) {
                FxModuleType.SATURATION -> {
                    wetOutput[0] = Drive.process(left, currentDepth)
                    wetOutput[1] = Drive.process(right, currentDepth)
                }
                FxModuleType.TREMOLO -> {
                    val leftAmp = 1.0 - currentDepth * (0.5 + 0.5 * oscSin)
                    val rightPhase = oscSin * rightOffsetCos + oscCos * rightOffsetSin
                    val rightAmp = 1.0 - currentDepth * (0.5 + 0.5 * rightPhase)
                    wetOutput[0] = (left * leftAmp).toFloat()
                    wetOutput[1] = (right * rightAmp).toFloat()
                    val nextSin = oscSin * stepCos + oscCos * stepSin
                    oscCos = oscCos * stepCos - oscSin * stepSin
                    oscSin = nextSin
                    renormalizeIn--
                    if (renormalizeIn == 0) {
                        val norm = 1.0 / sqrt(oscSin * oscSin + oscCos * oscCos)
                        oscSin *= norm
                        oscCos *= norm
                        renormalizeIn = 4_096
                    }
                }
                FxModuleType.CHORUS -> chorus.process(left, right, wetOutput, 0)
                FxModuleType.DELAY -> delay.process(left, right, wetOutput, 0)
            }
            output[0] = blend(left, wetOutput[0], wet)
            output[1] = blend(right, wetOutput[1], wet)
        }

        fun reset() {
            requested = FxModuleConfig()
            active = requested
            chorus.resetForBypass()
            delay.resetForBypass()
            wet = 0f
            targetWet = 0f
            wetStep = 0f
            remainingFrames = 0
            pendingTypeSwitch = false
            historyCleared = true
            currentDepth = active.depth
            oscSin = 0.0
            oscCos = 1.0
            renormalizeIn = 4_096
        }

        private fun blend(dry: Float, processed: Float, mix: Float): Float {
            val safe = if (processed.isFinite()) processed.coerceIn(-8f, 8f) else 0f
            return (dry * (1f - mix) + safe * mix).coerceIn(-1f, 1f)
        }
    }
}
