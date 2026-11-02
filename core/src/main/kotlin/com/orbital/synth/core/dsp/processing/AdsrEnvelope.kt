package com.orbital.synth.core.dsp.processing

import kotlin.math.roundToInt
import kotlin.math.max

enum class EnvelopeStage { IDLE, ATTACK, DECAY, SUSTAIN, RELEASE }

/**
 * Sample-count-accurate linear ADSR. No callbacks, locks or allocations per sample.
 * One instance per voice; parameters and note events are supplied by the audio thread.
 */
class AdsrEnvelope(sampleRate: Int = 48_000) {
    init { require(sampleRate in 8_000..384_000) }
    private val sampleRate = sampleRate
    var stage: EnvelopeStage = EnvelopeStage.IDLE
        private set
    var level: Float = 0f
        private set
    val isActive: Boolean get() = stage != EnvelopeStage.IDLE

    private var attackSamples = 480
    private var decaySamples = 4_800
    private var releaseSamples = 9_600
    private var sustain = 0.7f
    private var remaining = 0
    private var increment = 0f

    init { configure(0.01f, 0.1f, 0.7f, 0.2f) }

    fun configure(attackSeconds: Float, decaySeconds: Float, sustainLevel: Float, releaseSeconds: Float) {
        val previousAttack = attackSamples
        val previousDecay = decaySamples
        val previousRelease = releaseSamples
        val previousSustain = sustain
        attackSamples = toSamples(attackSeconds)
        decaySamples = toSamples(decaySeconds)
        releaseSamples = toSamples(releaseSeconds)
        sustain = sanitizeLevel(sustainLevel)
        // Voice controls can change during a held note. Retarget a moving segment
        // from its *current* level, so changing attack/decay/release times or
        // sustain while a segment is active does not create an amplitude step.
        // Unchanged parameters leave the countdown completely untouched.
        when (stage) {
            EnvelopeStage.ATTACK -> if (attackSamples != previousAttack) {
                if (attackSamples == 0) {
                    level = 1f
                    beginDecay()
                } else {
                    remaining = rescaleRemaining(remaining, previousAttack, attackSamples)
                    increment = (1f - level) / remaining
                }
            }
            EnvelopeStage.DECAY -> if (decaySamples != previousDecay || sustain != previousSustain) {
                if (decaySamples == 0) {
                    level = sustain
                    stage = EnvelopeStage.SUSTAIN
                } else {
                    if (decaySamples != previousDecay) {
                        remaining = rescaleRemaining(remaining, previousDecay, decaySamples)
                    }
                    increment = (sustain - level) / remaining
                }
            }
            EnvelopeStage.SUSTAIN -> level = sustain
            EnvelopeStage.RELEASE -> if (releaseSamples != previousRelease) {
                if (releaseSamples == 0) {
                    reset()
                } else {
                    remaining = rescaleRemaining(remaining, previousRelease, releaseSamples)
                    increment = -level / remaining
                }
            }
            EnvelopeStage.IDLE -> Unit
        }
    }

    /** Reset to zero on a retrigger; preserve the level on a legato note. */
    fun noteOn(retrigger: Boolean = true) {
        if (retrigger) level = 0f
        if (attackSamples == 0) {
            level = 1f
            beginDecay()
        } else {
            stage = EnvelopeStage.ATTACK
            remaining = attackSamples
            increment = (1f - level) / remaining
        }
    }

    fun noteOff() {
        if (stage == EnvelopeStage.IDLE || stage == EnvelopeStage.RELEASE) return
        if (releaseSamples == 0) {
            reset()
        } else {
            stage = EnvelopeStage.RELEASE
            remaining = releaseSamples
            increment = -level / remaining
        }
    }

    fun reset() {
        stage = EnvelopeStage.IDLE
        remaining = 0
        increment = 0f
        level = 0f
    }

    /** Returns the *new* amplitude, including the final endpoint of each segment. */
    fun next(): Float {
        when (stage) {
            EnvelopeStage.IDLE -> return 0f
            EnvelopeStage.SUSTAIN -> return sustain
            EnvelopeStage.ATTACK, EnvelopeStage.DECAY, EnvelopeStage.RELEASE -> {
                level = (level + increment).coerceIn(0f, 1f)
                remaining--
                if (remaining == 0) {
                    when (stage) {
                        EnvelopeStage.ATTACK -> { level = 1f; beginDecay() }
                        EnvelopeStage.DECAY -> { level = sustain; stage = EnvelopeStage.SUSTAIN }
                        EnvelopeStage.RELEASE -> reset()
                        else -> Unit
                    }
                }
            }
        }
        return level
    }

    private fun beginDecay() {
        if (decaySamples == 0) {
            level = sustain
            stage = EnvelopeStage.SUSTAIN
        } else {
            stage = EnvelopeStage.DECAY
            remaining = decaySamples
            increment = (sustain - level) / remaining
        }
    }

    private fun toSamples(seconds: Float): Int {
        val safe = (if (seconds.isFinite()) seconds else 0f).coerceIn(0f, 30f)
        return (safe * sampleRate).roundToInt()
    }

    private fun rescaleRemaining(current: Int, oldDuration: Int, newDuration: Int): Int =
        max(1, if (oldDuration == 0) newDuration else
            ((current.toLong() * newDuration + oldDuration / 2) / oldDuration).toInt())

    private fun sanitizeLevel(value: Float): Float =
        (if (value.isFinite()) value else 0f).coerceIn(0f, 1f)
}
