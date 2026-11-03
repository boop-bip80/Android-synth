package com.orbital.synth.core

import com.orbital.synth.core.dsp.processing.LfoShape
import com.orbital.synth.core.dsp.processing.ModCurve
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import com.orbital.synth.core.dsp.processing.ModulationStepConfig
import com.orbital.synth.core.dsp.processing.StepDirection
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class LiveModulationIntegrationTest {
    private fun synth(): OrbitalSynth = OrbitalSynth(sampleRate = 8_000, maxVoices = 4).apply {
        setParameter(ParameterId.WAVE, 0.43f)
        setParameter(ParameterId.MORPH, 0.15f)
        setParameter(ParameterId.CUTOFF, 0.98f)
        setParameter(ParameterId.RESONANCE, 0.1f)
        setParameter(ParameterId.MOVEMENT, 0f)
        setParameter(ParameterId.LFO_DEPTH, 0f)
        setParameter(ParameterId.FX_AMOUNT, 0f)
        setParameter(ParameterId.ATTACK, 0f)
        setParameter(ParameterId.SUSTAIN, 1f)
        setParameter(ParameterId.RELEASE, 0f)
    }

    @Test fun noRouteAndClearedRoutePreserveLegacySamplesExactly() {
        val baseline = synth()
        val edited = synth()
        edited.setModulationRoute(0, ModSource.TOUCH_X, ModDestination.MORPH, 0.75f)
        edited.clearModulationRoute(0)
        edited.setModulationSource(ModSource.TOUCH_X, 1f)
        edited.setModulationLfo(0, true, LfoShape.SQUARE, rateHz = 12f)
        edited.setModulationTempoBpm(133f)
        assertFalse(edited.modulationRoute(0).enabled)
        assertEquals(64, edited.modulationRouteCount)
        assertEquals(8, edited.modulationLfoCount)
        baseline.noteOn(61)
        edited.noteOn(61)
        val one = FloatArray(16_384)
        val two = FloatArray(one.size)
        baseline.renderStereo(one, one.size / 2)
        edited.renderStereo(two, two.size / 2)
        assertArrayEquals("No new route must preserve the previous synth sound",
            one, two, 0f)
    }

    @Test fun touchMacroToMorphAudiblyChangesLivePcm() {
        val plain = synth()
        val modded = synth().apply {
            setModulationRoute(0, ModSource.MACRO1, ModDestination.MORPH, 0.8f)
            setModulationSource(ModSource.MACRO1, 1f)
        }
        plain.noteOn(60)
        modded.noteOn(60)
        val one = FloatArray(8_192 * 2)
        val two = FloatArray(one.size)
        plain.renderStereo(one, one.size / 2)
        modded.renderStereo(two, two.size / 2)
        var difference = 0.0
        for (i in one.indices) difference += abs((one[i] - two[i]).toDouble())
        assertTrue("Touch-driven morph must affect real PCM output", difference > 5.0)
        assertBounded(two)
    }

    @Test fun routedPitchChangesActualZeroCrossingRate() {
        val plain = synth().apply {
            setParameter(ParameterId.WAVE, 0f)
            setParameter(ParameterId.MORPH, 0f)
        }
        val modded = synth().apply {
            setParameter(ParameterId.WAVE, 0f)
            setParameter(ParameterId.MORPH, 0f)
            setModulationRoute(0, ModSource.TOUCH_Y, ModDestination.PITCH, 0.25f)
            setModulationSource(ModSource.TOUCH_Y, 1f)
        }
        plain.noteOn(69)
        modded.noteOn(69)
        val one = FloatArray(8_000 * 2)
        val two = FloatArray(one.size)
        plain.renderStereo(one, 8_000)
        modded.renderStereo(two, 8_000)
        val a = upwardCrossings(one, 2_000, 7_800)
        val b = upwardCrossings(two, 2_000, 7_800)
        assertTrue("Positive pitch modulation must raise the sounding note: $a -> $b",
            b > a * 1.12)
        assertBounded(two)
    }

    @Test fun disabledLfoIsSilentAndClearingRestoresDryParityOnNextBlock() {
        val dry = synth()
        val routed = synth()
        routed.setModulationRoute(0, ModSource.LFO2, ModDestination.MORPH, 0.8f)
        routed.setModulationLfo(1, enabled = false, shape = LfoShape.SINE, rateHz = 14f)
        dry.noteOn(64)
        routed.noteOn(64)
        val one = FloatArray(8_192)
        val two = FloatArray(one.size)
        dry.renderStereo(one, 4_096)
        routed.renderStereo(two, 4_096)
        assertArrayEquals("Disabled LFO must be neutral", one, two, 0f)
        routed.clearModulationRoute(0)
        dry.renderStereo(one, 4_096)
        routed.renderStereo(two, 4_096)
        assertArrayEquals("After clear, default render path must be sample-identical", one, two, 0f)
    }

    @Test fun routedSineIsContinuousAcrossBufferSizes() {
        val one = synth()
        val chunks = synth()
        for (synth in arrayOf(one, chunks)) {
            synth.setModulationRoute(0, ModSource.LFO1, ModDestination.MORPH,
                0.25f, ModCurve.EXPONENTIAL)
            synth.setModulationLfo(0, true, LfoShape.SINE, rateHz = 3f)
            synth.noteOn(65)
        }
        val large = FloatArray(4_800 * 2)
        val small = FloatArray(480 * 2)
        val combined = FloatArray(large.size)
        one.renderStereo(large, 4_800)
        repeat(10) { block ->
            chunks.renderStereo(small, 480)
            System.arraycopy(small, 0, combined, block * small.size, small.size)
        }
        assertArrayEquals(large, combined, 1e-6f)
    }

    @Test fun rapidEditorAutomationDoesNotLeaveStuckNotesOrEmitInvalidSamples() {
        val machine = synth()
        val audio = FloatArray(256 * 2)
        machine.noteOn(60)
        machine.noteOn(67)
        for (block in 0 until 160) {
            machine.setModulationRoute(0, ModSource.TOUCH_PRESSURE,
                ModDestination.PITCH, if (block % 2 == 0) 0.4f else -0.4f)
            machine.setModulationSource(ModSource.TOUCH_PRESSURE, if (block % 2 == 0) 1f else -1f)
            machine.setModulationLfo(0, true,
                if (block % 3 == 0) LfoShape.SAMPLE_HOLD else LfoShape.TRIANGLE,
                rateHz = (block % 32 + 1).toFloat(), retriggerOnNote = true)
            machine.renderStereo(audio, 256)
            assertBounded(audio)
        }
        machine.clearModulationRoute(0)
        machine.allNotesOff()
        machine.renderStereo(audio, 256)
        machine.renderStereo(audio, 256)
        assertEquals(0, machine.activeVoiceCount)
    }

    @Test fun idleAudioFastPathAdvancesLiveLfoClock() {
        val one = synth()
        val chunks = synth()
        for (machine in arrayOf(one, chunks)) {
            machine.setModulationRoute(0, ModSource.LFO1, ModDestination.MORPH, 0.35f)
            machine.setModulationLfo(0, true, LfoShape.SINE, rateHz = 4f)
        }
        val bigSilent = FloatArray(8_000 * 2)
        val smallSilent = FloatArray(80 * 2)
        one.renderStereo(bigSilent, 8_000)
        repeat(100) { chunks.renderStereo(smallSilent, 80) }
        assertTrue(bigSilent.all { it == 0f })
        one.noteOn(69)
        chunks.noteOn(69)
        val a = FloatArray(2_048)
        val b = FloatArray(a.size)
        one.renderStereo(a, a.size / 2)
        chunks.renderStereo(b, b.size / 2)
        assertArrayEquals("Silent LFO clock must not depend on block size", a, b, 0.0001f)
    }

    @Test fun routedCutoffChangesActualFilterPcmAcrossAllFiveEngines() {
        for (engine in 0..4) {
            val dry = synth().apply {
                setParameter(ParameterId.ENGINE, engine / 4f)
                setParameter(ParameterId.CUTOFF, 0.76f)
                setParameter(ParameterId.RESONANCE, 0.2f)
            }
            val routed = synth().apply {
                setParameter(ParameterId.ENGINE, engine / 4f)
                setParameter(ParameterId.CUTOFF, 0.76f)
                setParameter(ParameterId.RESONANCE, 0.2f)
                setModulationRoute(0, ModSource.TOUCH_X, ModDestination.CUTOFF, -0.60f)
                setModulationSource(ModSource.TOUCH_X, 1f)
            }
            dry.noteOn(60)
            routed.noteOn(60)
            val baseline = FloatArray(4_096 * 2)
            val affected = FloatArray(baseline.size)
            dry.renderStereo(baseline, 4_096)
            routed.renderStereo(affected, 4_096)
            assertTrue("Engine $engine cutoff route must change the filtered PCM",
                pcmDifference(baseline, affected) > 1.0)
            assertBounded(affected)
        }
    }

    @Test fun resonanceHasItsOwnAudibleRouteWithoutCutoffChanges() {
        val dry = synth().apply {
            setParameter(ParameterId.CUTOFF, 0.55f)
            setParameter(ParameterId.RESONANCE, 0.04f)
        }
        val routed = synth().apply {
            setParameter(ParameterId.CUTOFF, 0.55f)
            setParameter(ParameterId.RESONANCE, 0.04f)
            setModulationRoute(0, ModSource.TOUCH_PRESSURE, ModDestination.RESONANCE, 0.85f)
            setModulationSource(ModSource.TOUCH_PRESSURE, 1f)
        }
        dry.noteOn(62)
        routed.noteOn(62)
        val plain = FloatArray(8_192)
        val shaped = FloatArray(plain.size)
        dry.renderStereo(plain, plain.size / 2)
        routed.renderStereo(shaped, shaped.size / 2)
        assertTrue("Resonance route must change actual filter poles",
            pcmDifference(plain, shaped) > 1.0)
        assertBounded(shaped)
    }

    @Test fun ampAndPanRoutesCanMuteAndBalanceLiveStereo() {
        val muted = synth().apply {
            setModulationRoute(0, ModSource.TOUCH_X, ModDestination.AMP, -0.5f)
            setModulationSource(ModSource.TOUCH_X, 1f)
        }
        muted.noteOn(60)
        val silence = FloatArray(4_096)
        muted.renderStereo(silence, silence.size / 2)
        assertTrue("An AMP=0 cable must mute the dry stereo path",
            silence.all { abs(it) < 0.000001f })

        val panned = synth().apply {
            setModulationRoute(0, ModSource.TOUCH_Y, ModDestination.PAN, 0.5f)
            setModulationSource(ModSource.TOUCH_Y, 1f)
        }
        panned.noteOn(60)
        val audio = FloatArray(4_096)
        panned.renderStereo(audio, audio.size / 2)
        var left = 0.0
        var right = 0.0
        for (frame in 0 until audio.size / 2) {
            left += abs(audio[frame * 2].toDouble())
            right += abs(audio[frame * 2 + 1].toDouble())
        }
        assertTrue("Full right PAN route must silence the left channel", left < 0.0001)
        assertTrue("Full right PAN route must preserve audible right channel", right > 1.0)
        assertBounded(audio)
    }

    @Test fun filterRoutesRespectClearedParityAndVariableRenderBlockSize() {
        val reference = synth()
        val removed = synth().apply {
            setModulationRoute(0, ModSource.LFO1, ModDestination.CUTOFF, 0.7f)
            clearModulationRoute(0)
        }
        reference.noteOn(69)
        removed.noteOn(69)
        val a = FloatArray(4_096)
        val b = FloatArray(a.size)
        reference.renderStereo(a, a.size / 2)
        removed.renderStereo(b, b.size / 2)
        assertArrayEquals("Cleared filter routes must leave baseline PCM bit-exact", a, b, 0f)

        val large = synth()
        val chunks = synth()
        for (machine in arrayOf(large, chunks)) {
            machine.setParameter(ParameterId.CUTOFF, 0.62f)
            machine.setParameter(ParameterId.RESONANCE, 0.18f)
            machine.setModulationRoute(0, ModSource.LFO1, ModDestination.CUTOFF, -0.37f)
            machine.setModulationRoute(1, ModSource.LFO2, ModDestination.RESONANCE, 0.54f)
            machine.setModulationLfo(0, true, LfoShape.TRIANGLE, rateHz = 6f)
            machine.setModulationLfo(1, true, LfoShape.SINE, rateHz = 8f)
            machine.noteOn(63)
        }
        val whole = FloatArray(4_800 * 2)
        val small = FloatArray(96 * 2)
        val assembled = FloatArray(whole.size)
        large.renderStereo(whole, 4_800)
        repeat(50) { block ->
            chunks.renderStereo(small, 96)
            System.arraycopy(small, 0, assembled, block * small.size, small.size)
        }
        assertArrayEquals("Filter modulation must be invariant to audio callback size",
            whole, assembled, 0.0001f)
        assertBounded(whole)
    }

    @Test fun allSixtyFourModulationCablesSaturateSafelyDuringTouchFrenzy() {
        val machine = synth()
        machine.setParameter(ParameterId.CUTOFF, 0.5f)
        machine.setParameter(ParameterId.RESONANCE, 0.4f)
        for (slot in 0 until machine.modulationRouteCount) {
            val destination = if (slot % 2 == 0)
                ModDestination.CUTOFF else ModDestination.RESONANCE
            machine.setModulationRoute(slot, ModSource.TOUCH_X, destination,
                if (slot % 3 == 0) -4f else 4f)
        }
        machine.noteOn(69)
        val audio = FloatArray(256 * 2)
        repeat(40) { block ->
            machine.setModulationSource(ModSource.TOUCH_X, if (block % 2 == 0) 1f else -1f)
            machine.renderStereo(audio, 256)
            assertBounded(audio)
        }
        for (slot in 0 until machine.modulationRouteCount) machine.clearModulationRoute(slot)
        machine.renderStereo(audio, 256)
        assertBounded(audio)
    }

    private fun pcmDifference(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        for (index in a.indices) sum += abs((a[index] - b[index]).toDouble())
        return sum
    }

    @Test fun realStepClockCanRhythmicallyMuteAmpAndAnimateActualPlayhead() {
        val machine = synth()
        machine.setModulationSteps(ModulationStepConfig(
            enabled = true,
            values = listOf(1f, -1f) + List(14) { 0f },
            length = 2, beatsPerStep = .25f, glide = 0f,
            direction = StepDirection.FORWARD,
        ))
        machine.setModulationTempoBpm(120f)
        machine.setModulationRoute(0, ModSource.STEP, ModDestination.AMP, 0.5f)
        machine.noteOn(60)
        val audio = FloatArray(3_000 * 2)
        machine.renderStereo(audio, 3_000)
        fun magnitude(start: Int, end: Int): Double {
            var sum = 0.0
            for (frame in start until end) {
                sum += abs(audio[frame * 2].toDouble()) + abs(audio[frame * 2 + 1].toDouble())
            }
            return sum
        }
        val high = magnitude(250, 900)
        val low = magnitude(1_250, 1_900)
        assertTrue("STEP=1 must be louder than STEP=-1: high=$high, low=$low",
            high > 1.0 && high > low * 8.0)
        assertEquals("Three sixteenth-note steps wrap back to second pad", 1,
            machine.modulationStepPlayhead)
        assertBounded(audio)
    }

    @Test fun stepToCutoffIsAudibleAndSilentClockMatchesRenderedClock() {
        val plain = synth().apply {
            setParameter(ParameterId.CUTOFF, 0.82f)
        }
        val programmed = synth().apply {
            setParameter(ParameterId.CUTOFF, 0.82f)
            setModulationSteps(ModulationStepConfig(
                enabled = true, values = List(16) { -1f }, length = 1,
                beatsPerStep = .25f, glide = 0f,
            ))
            setModulationRoute(0, ModSource.STEP, ModDestination.CUTOFF, 0.5f)
        }
        plain.noteOn(62)
        programmed.noteOn(62)
        val reference = FloatArray(8_192)
        val modified = FloatArray(reference.size)
        plain.renderStereo(reference, reference.size / 2)
        programmed.renderStereo(modified, modified.size / 2)
        var difference = 0.0
        for (i in reference.indices) difference += abs((reference[i] - modified[i]).toDouble())
        assertTrue("A real STEP cable must change filter PCM", difference > 1.0)
        assertBounded(modified)

        val clockOnly = synth()
        val untouched = synth()
        clockOnly.setModulationSteps(ModulationStepConfig(
            enabled = true, length = 4, beatsPerStep = .25f,
        ))
        clockOnly.setModulationTempoBpm(120f)
        clockOnly.noteOn(64)
        untouched.noteOn(64)
        val one = FloatArray(2_500 * 2)
        val two = FloatArray(one.size)
        clockOnly.renderStereo(one, 2_500)
        untouched.renderStereo(two, 2_500)
        assertArrayEquals("Unrouted STEP must preserve the existing audio output",
            two, one, 0f)
        assertEquals(2, clockOnly.modulationStepPlayhead)
    }

    @Test fun stepSequencePcmIsIndependentOfAudioCallbackSize() {
        val one = synth()
        val chunks = synth()
        for (machine in arrayOf(one, chunks)) {
            machine.setModulationSteps(ModulationStepConfig(
                enabled = true, values = List(16) { if (it % 2 == 0) 1f else -1f },
                length = 16, beatsPerStep = .125f, glide = .35f,
            ))
            machine.setModulationRoute(0, ModSource.STEP, ModDestination.MORPH, 0.35f)
            machine.noteOn(65)
        }
        val big = FloatArray(4_800 * 2)
        val small = FloatArray(96 * 2)
        val combined = FloatArray(big.size)
        one.renderStereo(big, 4_800)
        repeat(50) { block ->
            chunks.renderStereo(small, 96)
            System.arraycopy(small, 0, combined, block * small.size, small.size)
        }
        assertArrayEquals(big, combined, .0001f)
        assertEquals(one.modulationStepPlayhead, chunks.modulationStepPlayhead)
        assertBounded(big)
    }

    private fun upwardCrossings(buffer: FloatArray, startFrame: Int, endFrame: Int): Int {
        var count = 0
        for (i in startFrame + 1 until endFrame) {
            val before = buffer[(i - 1) * 2]
            val after = buffer[i * 2]
            if (before <= 0f && after > 0f) count++
        }
        return count
    }

    private fun assertBounded(audio: FloatArray) {
        for (sample in audio) {
            assertTrue("Non-finite audio sample", sample.isFinite())
            assertTrue("Audio escaped stereo bounds", abs(sample) <= 1.0001f)
        }
    }
}
