package com.orbital.synth.ui

import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Static, deterministic parameter diagrams for the seven *real* FX processors.
 *
 * These are conceptual transfer/envelope/diffusion diagrams. They are deliberately
 * not audio captures, measured impulse responses, spectra, FFTs or RMS meters.
 * Work is bounded (<= 4 x 49 points) and happens on Compose state changes only.
 */
internal enum class FxSignatureInk { CYAN, VIOLET, GOLD }

internal data class FxSignaturePoint(val x: Float, val y: Float)

internal data class FxSignatureTrace(
    val ink: FxSignatureInk,
    val points: List<FxSignaturePoint>,
)

internal data class FxSignatureSnapshot(
    val type: FxModuleType,
    val engaged: Boolean,
    val wet: Float,
    val depth: Float,
    val rate: Float,
    val stereo: Float,
    val heading: String,
    val conceptualLabel: String,
    val accessibleDescription: String,
    val traces: List<FxSignatureTrace>,
)

internal object FxParameterSignatureModel {
    private const val SAMPLE_COUNT = 48
    private val tau = 2.0 * PI

    private fun curve(
        ink: FxSignatureInk,
        shape: (Double) -> Double,
    ): FxSignatureTrace = FxSignatureTrace(ink, List(SAMPLE_COUNT + 1) { sample ->
        val t = sample.toDouble() / SAMPLE_COUNT
        val v = shape(t)
        FxSignaturePoint(t.toFloat(),
            (if (v.isFinite()) v.toFloat() else .5f).coerceIn(.05f, .95f))
    })

    /** Speak real saved settings without calculating any preview geometry. */
    fun accessibleDescription(raw: FxModuleConfig): String {
        val wet = ModularFxUiModel.sanitized(raw.wet)
        val depth = ModularFxUiModel.sanitized(raw.depth)
        val rate = ModularFxUiModel.sanitized(raw.rate)
        val stereo = ModularFxUiModel.sanitized(raw.stereo)
        return buildString {
            append(ModularFxUiModel.title(raw.type))
            append(if (raw.enabled) ", configured and enabled" else ", bypassed")
            append(", dry wet ")
            append(ModularFxUiModel.wetDisplay(wet))
            append(", ")
            append(ModularFxUiModel.depthName(raw.type))
            append(" ")
            append(ModularFxUiModel.depthDisplay(raw.type, depth))
            if (ModularFxUiModel.usesRate(raw.type)) {
                append(", ")
                append(ModularFxUiModel.rateName(raw.type))
                append(" ")
                append(ModularFxUiModel.rateDisplay(raw.type, rate))
            }
            if (ModularFxUiModel.usesStereo(raw.type)) {
                append(", ")
                append(ModularFxUiModel.stereoName(raw.type))
                append(" ")
                append(ModularFxUiModel.stereoDisplay(raw.type, stereo))
            }
            append(". Illustrative parameter geometry only; not measured PCM or FFT.")
        }
    }

    fun build(raw: FxModuleConfig): FxSignatureSnapshot {
        val wet = ModularFxUiModel.sanitized(raw.wet)
        val depth = ModularFxUiModel.sanitized(raw.depth)
        val rate = ModularFxUiModel.sanitized(raw.rate)
        val stereo = ModularFxUiModel.sanitized(raw.stereo)
        val engaged = ModularFxUiModel.configured(raw) && raw.enabled && wet > 0f
        val d = depth.toDouble()
        val r = rate.toDouble()
        val s = stereo.toDouble()
        val details = accessibleDescription(raw)

        val label: String
        val traces: List<FxSignatureTrace>
        when (raw.type) {
            FxModuleType.SATURATION -> {
                label = "SOFT-CLIP TRANSFER / INPUT → OUTPUT"
                // Drive affects a true static nonlinearity; unused rate/stereo
                // cannot change this drawing or falsely imply extra processors.
                val gain = 1.0 + d * 11.0
                traces = listOf(
                    curve(FxSignatureInk.GOLD) { t -> .82 - .64 * t },
                    curve(FxSignatureInk.CYAN) { t ->
                        val x = t * 2 - 1
                        val driven = gain * x
                        .5 - .35 * driven / sqrt(1 + driven * driven)
                    },
                )
            }
            FxModuleType.TREMOLO -> {
                label = "AMPLITUDE ENVELOPE / L–R PHASE"
                val cycles = 1.0 + r * 4.0
                fun pulse(t: Double, phase: Double): Double {
                    val envelope = 1 - d * .9 * (.5 - .5 * sin(tau * cycles * t + phase))
                    return .85 - envelope * .66
                }
                traces = listOf(
                    curve(FxSignatureInk.CYAN) { t -> pulse(t, 0.0) },
                    curve(FxSignatureInk.VIOLET) { t -> pulse(t, s * PI) },
                )
            }
            FxModuleType.CHORUS -> {
                label = "THREE MODULATED DELAY VOICES / SPREAD"
                val cycles = 1.0 + r * 3.0
                val excursion = .035 + d * .23
                traces = listOf(
                    curve(FxSignatureInk.CYAN) { t ->
                        .5 + excursion * sin(tau * cycles * t)
                    },
                    curve(FxSignatureInk.VIOLET) { t ->
                        .5 + excursion * sin(tau * cycles * t + .35 + s * 2.0)
                    },
                    curve(FxSignatureInk.GOLD) { t ->
                        .5 + excursion * sin(tau * cycles * t - .35 - s * 2.0)
                    },
                )
            }
            FxModuleType.DELAY -> {
                label = "FEEDBACK ECHO TAPS / L–R PING-PONG"
                val spacing = .12 + r * .048
                val feedback = .05 + d * .83
                fun echo(t: Double, right: Boolean): Double {
                    var sum = 0.0
                    for (tap in 0..5) {
                        val center = .09 + tap * spacing
                        if (center > 1.02) break
                        val triangle = max(0.0, 1.0 - abs(t - center) / .025)
                        val alternate = if ((tap % 2 == 1) == right) 1.0
                            else 1.0 - s * .75
                        sum += triangle * feedback.pow(tap) * alternate
                    }
                    return .86 - min(1.0, sum) * .64
                }
                traces = listOf(
                    curve(FxSignatureInk.CYAN) { t -> echo(t, false) },
                    curve(FxSignatureInk.VIOLET) { t -> echo(t, true) },
                )
            }
            FxModuleType.REVERB -> {
                label = "DECAY FIELD / HIGH-FREQUENCY DAMPING"
                val envelope = 1.0 + (1 - d) * 5.0
                val cycles = 4.0 + (1 - r) * 5.0
                traces = listOf(
                    curve(FxSignatureInk.CYAN) { t ->
                        .5 + .35 * exp(-envelope * t) * sin(tau * cycles * t)
                    },
                    curve(FxSignatureInk.VIOLET) { t ->
                        .5 + .35 * exp(-envelope * t) *
                            sin(tau * cycles * t + .5 + s * 1.7)
                    },
                    curve(FxSignatureInk.GOLD) { t ->
                        .5 + .14 * exp(-envelope * .6 * t) * cos(tau * 2 * t)
                    },
                )
            }
            FxModuleType.PHASER -> {
                label = "FOUR ALLPASS SWEEP STAGES / STEREO OFFSET"
                val cycles = .8 + r * 3.0
                val excursion = .045 + d * .23
                traces = (0..3).map { band ->
                    curve(when (band) {
                        0, 3 -> FxSignatureInk.CYAN
                        1 -> FxSignatureInk.VIOLET
                        else -> FxSignatureInk.GOLD
                    }) { t ->
                        .5 + excursion *
                            sin(tau * cycles * t + band * .48 + (band % 2) * s * PI) *
                            (.65 + .35 * cos(tau * t + band))
                    }
                }
            }
            FxModuleType.BITCRUSH -> {
                label = "BIT QUANTIZATION / SAMPLE-HOLD STAIRCASE"
                val bits = (16f - depth * 12f).roundToInt().coerceIn(4, 16)
                val rightBits = (bits - stereo * 4f).roundToInt().coerceIn(4, 16)
                val holdSteps = (8f + rate * 54f).roundToInt().coerceIn(8, 62)
                fun quantized(t: Double, sampleBits: Int): Double {
                    val heldTime = floor(t * holdSteps) / holdSteps
                    val analog = sin(tau * 2.0 * heldTime)
                    val levels = (1 shl (sampleBits - 1)) - 1
                    return (analog * levels).roundToInt().toDouble() / levels
                }
                traces = listOf(
                    curve(FxSignatureInk.GOLD) { t -> .5 - .29 * sin(tau * 2 * t) },
                    curve(FxSignatureInk.CYAN) { t -> .5 - .29 * quantized(t, bits) },
                    curve(FxSignatureInk.VIOLET) { t -> .5 - .29 * quantized(t, rightBits) },
                )
            }
        }
        return FxSignatureSnapshot(
            type = raw.type,
            engaged = engaged,
            wet = wet,
            depth = depth,
            rate = rate,
            stereo = stereo,
            heading = ModularFxUiModel.shortName(raw.type),
            conceptualLabel = label,
            accessibleDescription = details,
            traces = traces,
        )
    }
}
