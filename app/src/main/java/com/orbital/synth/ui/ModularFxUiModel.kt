package com.orbital.synth.ui

import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Read-only presentation math for the real normalized DSP parameters.
 *
 * The absent-module sentinel is [FxModuleConfig]'s default value. A bypassed
 * module keeps its configured wet/depth/rate/stereo values and is not empty.
 * No FX signal or audio samples are inferred or generated in this model.
 */
internal object ModularFxUiModel {
    private val emptyConfig = FxModuleConfig()

    fun sanitized(value: Float): Float =
        (if (value.isFinite()) value else 0f).coerceIn(0f, 1f)

    fun configured(config: FxModuleConfig): Boolean = config != emptyConfig

    fun firstFreeIndex(slots: List<FxModuleConfig>): Int =
        slots.take(8).indexOfFirst { !configured(it) }

    fun newModule(type: FxModuleType): FxModuleConfig =
        FxModuleConfig(
            type = type,
            enabled = true,
            wet = 0.44f,
            depth = 0.5f,
            rate = 0.5f,
            stereo = 0.5f,
        )

    fun title(type: FxModuleType): String = when (type) {
        FxModuleType.SATURATION -> "HARMONIC DRIVE"
        FxModuleType.TREMOLO -> "PULSE TREMOLO"
        FxModuleType.CHORUS -> "PRISM CHORUS"
        FxModuleType.DELAY -> "SPACE DELAY"
        FxModuleType.REVERB -> "HALO REVERB"
        FxModuleType.PHASER -> "SPECTRAL PHASER"
        FxModuleType.BITCRUSH -> "PIXEL CRUSH"
    }

    fun shortName(type: FxModuleType): String = when (type) {
        FxModuleType.SATURATION -> "DRIVE"
        FxModuleType.TREMOLO -> "TREMOLO"
        FxModuleType.CHORUS -> "CHORUS"
        FxModuleType.DELAY -> "DELAY"
        FxModuleType.REVERB -> "REVERB"
        FxModuleType.PHASER -> "PHASER"
        FxModuleType.BITCRUSH -> "CRUSH"
    }

    fun description(type: FxModuleType): String = when (type) {
        FxModuleType.SATURATION -> "Soft-wave harmonics and warm edge"
        FxModuleType.TREMOLO -> "Amplitude pulses with stereo phase offset"
        FxModuleType.CHORUS -> "Independent modulated stereo delay voices"
        FxModuleType.DELAY -> "Wide tap echoes with feedback and ping-pong"
        FxModuleType.REVERB -> "Bounded eight-line stereo FDN room and decay"
        FxModuleType.PHASER -> "Four moving allpass stages with stereo feedback"
        FxModuleType.BITCRUSH -> "Intentional aliasing, sample hold and reduced bits"
    }

    fun usesRate(type: FxModuleType): Boolean = type != FxModuleType.SATURATION
    fun usesStereo(type: FxModuleType): Boolean = type != FxModuleType.SATURATION

    fun depthName(type: FxModuleType): String = when (type) {
        FxModuleType.SATURATION -> "DRIVE"
        FxModuleType.TREMOLO -> "PULSE DEPTH"
        FxModuleType.CHORUS -> "MOD DEPTH"
        FxModuleType.DELAY -> "FEEDBACK"
        FxModuleType.REVERB -> "DECAY"
        FxModuleType.PHASER -> "SWEEP"
        FxModuleType.BITCRUSH -> "BIT DEPTH"
    }

    fun rateName(type: FxModuleType): String = when (type) {
        FxModuleType.SATURATION -> "RATE (NOT USED)"
        FxModuleType.TREMOLO -> "PULSE RATE"
        FxModuleType.CHORUS -> "CHORUS SPEED"
        FxModuleType.DELAY -> "ECHO TIME"
        FxModuleType.REVERB -> "DAMPING"
        FxModuleType.PHASER -> "SWEEP RATE"
        FxModuleType.BITCRUSH -> "SAMPLE HOLD RATE"
    }

    fun stereoName(type: FxModuleType): String = when (type) {
        FxModuleType.SATURATION -> "STEREO (NOT USED)"
        FxModuleType.TREMOLO -> "STEREO PHASE"
        FxModuleType.CHORUS -> "STEREO WIDTH"
        FxModuleType.DELAY -> "PING-PONG"
        FxModuleType.REVERB -> "ROOM WIDTH"
        FxModuleType.PHASER -> "L/R PHASE OFFSET"
        FxModuleType.BITCRUSH -> "L/R BIT CONTRAST"
    }

    fun wetDisplay(value: Float): String = (sanitized(value) * 100f).roundToInt().toString() + "%"
    fun depthDisplay(type: FxModuleType, value: Float): String {
        val safe = sanitized(value)
        return when (type) {
            FxModuleType.DELAY -> (safe * 88f).roundToInt().toString() + "%"
            FxModuleType.REVERB ->
                String.format(Locale.US, "%.1f s", 0.2f + safe * 7.8f)
            FxModuleType.BITCRUSH ->
                (16f - safe * 12f).roundToInt().coerceIn(4, 16).toString() + " bits"
            else -> (safe * 100f).roundToInt().toString() + "%"
        }
    }

    fun rateDisplay(type: FxModuleType, value: Float): String {
        val safe = sanitized(value)
        return when (type) {
            FxModuleType.SATURATION -> "NOT USED"
            FxModuleType.TREMOLO ->
                String.format(Locale.US, "%.2f Hz", 0.05 * 2.0.pow(safe.toDouble() * 8.0))
            FxModuleType.CHORUS ->
                String.format(Locale.US, "%.2f Hz", 0.05 + safe * 6.0)
            FxModuleType.DELAY ->
                (25f + 1150f * safe).roundToInt().toString() + " ms"
            FxModuleType.REVERB ->
                (safe * 100f).roundToInt().toString() + "% DARK"
            FxModuleType.PHASER ->
                String.format(Locale.US, "%.2f Hz", 0.04 * 2.0.pow(safe.toDouble() * 7.65))
            FxModuleType.BITCRUSH ->
                (250.0 * 80.0.pow(safe.toDouble())).roundToInt().toString() + " Hz"
        }
    }

    fun stereoDisplay(type: FxModuleType, value: Float): String {
        val scaled = sanitized(value)
        return when (type) {
            FxModuleType.SATURATION -> "NOT USED"
            FxModuleType.TREMOLO, FxModuleType.PHASER ->
                (scaled * 180f).roundToInt().toString() + "°"
            FxModuleType.CHORUS, FxModuleType.DELAY, FxModuleType.REVERB ->
                wetDisplay(scaled)
            FxModuleType.BITCRUSH ->
                String.format(Locale.US, "%+.1f bit L/R", (scaled - 0.5f) * 4f)
        }
    }
}
