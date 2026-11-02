package com.orbital.synth.core.dsp.digital

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Automation regression cases: no callback allocation or render-block assumptions. */
class DigitalParameterAutomationTest {
    @Test fun fmAlgorithmSwitchBridgesItsFirstOutputSample() {
        val voice = SixOperatorFmVoice(48_000)
        voice.noteOn(69)
        repeat(2_000) { voice.nextSample() }
        val previous = voice.nextSample()
        voice.configure(FmAlgorithm.BELL, character = 1f, feedback = 1f)
        val first = voice.nextSample()
        assertEquals("routing change must not create a one-sample step",
            previous, first, 0.000001f)
        repeat(10_000) {
            val sample = voice.nextSample()
            assertTrue(sample.isFinite() && abs(sample) <= 1f)
        }
    }

    @Test fun phaseDistortionShapeSwitchBridgesItsFirstOutputSample() {
        val voice = PhaseDistortionVoice(48_000)
        voice.noteOn(69)
        repeat(2_000) { voice.nextSample() }
        val previous = voice.nextSample()
        voice.configure(PhaseDistortionShape.GLASS, character = 0f, drive = 1f)
        val first = voice.nextSample()
        assertEquals("shape change must not create a one-sample step",
            previous, first, 0.000001f)
        repeat(10_000) {
            val sample = voice.nextSample()
            assertTrue(sample.isFinite() && abs(sample) <= 1f)
        }
    }

    @Test fun fmParameterAutomationDoesNotDependOnCallerBlockBoundaries() {
        val oneBlock = renderFm(intArrayOf(6_000))
        val manyBlocks = renderFm(IntArray(200) { 30 })
        assertArrayEquals(oneBlock, manyBlocks, 0f)
    }

    @Test fun phaseDistortionAutomationDoesNotDependOnCallerBlockBoundaries() {
        val oneBlock = renderPd(intArrayOf(6_000))
        val manyBlocks = renderPd(IntArray(120) { 50 })
        assertArrayEquals(oneBlock, manyBlocks, 0f)
    }

    private fun renderFm(segments: IntArray): FloatArray {
        val voice = SixOperatorFmVoice(48_000)
        voice.noteOn(60)
        val result = FloatArray(segments.sum())
        var frame = 0
        for (segment in segments) repeat(segment) {
            when (frame) {
                380 -> voice.configure(FmAlgorithm.ELECTRIC_PIANO, 0.15f, 0f)
                1_300 -> voice.configure(FmAlgorithm.SIX_STACK, 1f, 1f)
                2_110 -> voice.configure(FmAlgorithm.NEBULA, 0.05f, 0.8f)
                3_700 -> voice.configure(FmAlgorithm.ADDITIVE, 0.7f, 0.15f)
                5_000 -> voice.configure(FmAlgorithm.BRASS, 0.9f, 0.4f)
            }
            result[frame] = voice.nextSample()
            frame++
        }
        return result
    }

    private fun renderPd(segments: IntArray): FloatArray {
        val voice = PhaseDistortionVoice(48_000)
        voice.noteOn(60)
        val result = FloatArray(segments.sum())
        var frame = 0
        for (segment in segments) repeat(segment) {
            when (frame) {
                380 -> voice.configure(PhaseDistortionShape.SAW, 0.1f, 0f)
                1_300 -> voice.configure(PhaseDistortionShape.FORMANT, 1f, 1f)
                2_110 -> voice.configure(PhaseDistortionShape.RESONANT, 0.05f, 0.8f)
                3_700 -> voice.configure(PhaseDistortionShape.GLASS, 0.7f, 0.15f)
                5_000 -> voice.configure(PhaseDistortionShape.DOUBLE_SINE, 0.9f, 0.4f)
            }
            result[frame] = voice.nextSample()
            frame++
        }
        return result
    }
}
