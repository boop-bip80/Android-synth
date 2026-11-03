package com.orbital.synth.core

import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ModularFxRuntimeTest {
    @Test fun emptyFxRackIsSampleForSampleLegacySound() {
        val original = clearTone()
        val withRack = clearTone()
        assertEquals(6, withRack.fxSlotCount)
        for (i in 0 until withRack.fxSlotCount) {
            assertFalse(withRack.fxSlot(i).enabled)
        }
        original.noteOn(69)
        withRack.noteOn(69)
        val left = FloatArray(8_192)
        val right = FloatArray(left.size)
        original.renderStereo(left, left.size / 2)
        withRack.renderStereo(right, right.size / 2)
        assertArrayEquals("Unused new FX must never alter default synthesis", left, right, 0f)
    }

    @Test fun realSynthAudioPassesThroughEnabledModularSaturation() {
        val dry = clearTone()
        val soundDesigned = clearTone()
        soundDesigned.setFxSlot(
            0, FxModuleType.SATURATION, enabled = true, wet = 1f, depth = 1f,
        )
        dry.noteOn(57)
        soundDesigned.noteOn(57)
        val a = FloatArray(6_144 * 2)
        val b = FloatArray(a.size)
        dry.renderStereo(a, a.size / 2)
        soundDesigned.renderStereo(b, b.size / 2)
        var change = 0.0
        for (index in a.indices) {
            change += abs((a[index] - b[index]).toDouble())
            assertTrue(b[index].isFinite())
            assertTrue(abs(b[index]) <= 1.00001f)
        }
        assertTrue("FX rack must be part of the audible OrbitalSynth bus", change > 80.0)
    }

    @Test fun effectsContinueProducingRealEchoesWhenNotesStop() {
        val dry = clearTone()
        val echoed = clearTone()
        echoed.setFxSlot(0, FxModuleType.DELAY,
            enabled = true, wet = 0.8f, depth = 0.85f, rate = 0f, stereo = 1f)
        for (synth in listOf(dry, echoed)) synth.noteOn(69, 0.8f)
        val first = FloatArray(4_800 * 2)
        dry.renderStereo(first, 4_800)
        echoed.renderStereo(first, 4_800)
        dry.noteOff(69)
        echoed.noteOff(69)
        val dryAfterRelease = FloatArray(9_600 * 2)
        val echoAfterRelease = FloatArray(dryAfterRelease.size)
        dry.renderStereo(dryAfterRelease, 9_600)
        echoed.renderStereo(echoAfterRelease, 9_600)
        assertEquals(0, dry.activeVoiceCount)
        assertEquals(0, echoed.activeVoiceCount)
        var extraTail = 0.0
        for (frame in 2_000 until 8_000) {
            extraTail += abs(echoAfterRelease[2 * frame].toDouble()) +
                abs(echoAfterRelease[2 * frame + 1].toDouble())
            assertTrue(echoAfterRelease[frame * 2].isFinite())
        }
        assertTrue("Delay tails should remain audible after voice release", extraTail > 1.0)
    }

    @Test fun hotSwitchingFxTypeFadesContinuously() {
        val oldType = clearTone()
        val switched = clearTone()
        for (synth in listOf(oldType, switched)) {
            synth.setFxSlot(0, FxModuleType.SATURATION, true, wet = 1f, depth = 1f)
            synth.noteOn(69)
        }
        val a = FloatArray(6_144)
        val b = FloatArray(a.size)
        oldType.renderStereo(a, a.size / 2)
        switched.renderStereo(b, b.size / 2)
        assertArrayEquals(a, b, 0f)
        switched.setFxSlot(0, FxModuleType.TREMOLO,
            enabled = true, wet = 1f, depth = 1f, rate = 0.65f, stereo = 0.8f)
        oldType.renderStereo(a, 1)
        switched.renderStereo(b, 1)
        assertTrue("The first switched frame must retain the preceding wet tone",
            abs(a[0] - b[0]) < 0.01f)
        oldType.renderStereo(a, a.size / 2)
        switched.renderStereo(b, b.size / 2)
        var diff = 0.0
        for (index in a.indices) diff += abs((a[index] - b[index]).toDouble())
        assertTrue("New type should become audible after transition", diff > 20.0)
    }

    @Test fun fxConfigSnapshotsValidateAndWorkerReorderingIsCoherent() {
        val synth = clearTone()
        synth.setFxSlot(0, FxModuleConfig(
            FxModuleType.SATURATION, true, wet = 3f, depth = -4f,
            rate = 10f, stereo = -2f,
        ))
        assertEquals(FxModuleConfig(
            FxModuleType.SATURATION, true, wet = 1f, depth = 0f,
            rate = 1f, stereo = 0f,
        ), synth.fxSlot(0))
        val before = synth.fxSlot(0)
        synth.setFxSlot(0, FxModuleConfig(FxModuleType.DELAY,
            true, wet = Float.NaN))
        assertEquals(before, synth.fxSlot(0))
        synth.setFxSlot(0, FxModuleType.SATURATION, true, 0.9f, 0.9f)
        synth.setFxSlot(1, FxModuleType.TREMOLO, true, 0.5f)
        synth.moveFxSlot(0, 1)
        synth.renderStereo(FloatArray(0), 0)
        assertEquals(FxModuleType.TREMOLO, synth.fxSlot(0).type)
        assertEquals(FxModuleType.SATURATION, synth.fxSlot(1).type)
        try {
            synth.fxSlot(6)
            throw AssertionError("Out-of-range slot must fail")
        } catch (_: IllegalArgumentException) { /* expected */ }
        synth.clearFxRack()
        assertFalse(synth.fxSlot(0).enabled)
        assertFalse(synth.fxSlot(1).enabled)
    }

    @Test fun liveFxRackIsBitIdenticalAcrossRenderChunking() {
        val large = clearTone()
        val chunks = clearTone()
        for (synth in listOf(large, chunks)) {
            synth.setFxSlot(0, FxModuleType.SATURATION, true, .75f, .8f)
            synth.setFxSlot(1, FxModuleType.TREMOLO, true, .55f, .65f, .74f, 1f)
            synth.setFxSlot(2, FxModuleType.CHORUS, true, .55f, .7f, .63f, 1f)
            synth.setFxSlot(3, FxModuleType.DELAY, true, .5f, .62f, .11f, 0.7f)
            synth.noteOn(62, 0.8f)
        }
        val frames = 8_192
        val whole = FloatArray(frames * 2)
        val partitioned = FloatArray(whole.size)
        val scratch = FloatArray(256 * 2)
        large.renderStereo(whole, frames)
        for (section in 0 until frames / 256) {
            chunks.renderStereo(scratch, 256)
            System.arraycopy(scratch, 0, partitioned,
                section * scratch.size, scratch.size)
        }
        assertArrayEquals("FX pipeline must be frame-indexed, not buffer-length-indexed",
            whole, partitioned, 0f)
    }

    @Test fun disablingEntireRackRestoresExactDryRenderingAfterFade() {
        val baseline = clearTone()
        val processed = clearTone()
        processed.setFxSlot(0, FxModuleType.DELAY, true, 0.8f, 0.6f, 0f)
        baseline.noteOn(60)
        processed.noteOn(60)
        val warmup = FloatArray(8_192)
        baseline.renderStereo(warmup, 4_096)
        processed.renderStereo(warmup, 4_096)
        processed.clearFxRack()
        baseline.renderStereo(warmup, 4_096)
        processed.renderStereo(warmup, 4_096)
        val dry = FloatArray(warmup.size)
        val after = FloatArray(dry.size)
        baseline.renderStereo(dry, 4_096)
        processed.renderStereo(after, 4_096)
        assertArrayEquals("After the 10ms bypass the old bus must be exactly dry",
            dry, after, 0f)
    }

    @Test fun highDensityRackAndRapidAutomationRemainBoundedWithoutStuckVoices() {
        val synth = clearTone().apply {
            for (slot in 0 until oscillatorSlotCount) {
                setOscillatorSlot(
                    slot, true, slot / 7f, 0.25f,
                    coarseSemitones = (slot - 4) * 3,
                )
            }
            for (slot in 0 until fxSlotCount) {
                setFxSlot(slot, FxModuleType.entries[slot % 4],
                    true, 0.22f, 0.45f, 0.15f, 0.65f)
            }
        }
        for (note in 45..69) synth.noteOn(note)
        val audio = FloatArray(256 * 2)
        repeat(40) { block ->
            synth.setFxSlot(0, FxModuleType.SATURATION, true,
                wet = (block % 10) / 10f, depth = 0.6f)
            synth.renderStereo(audio, 256)
            for (value in audio) {
                assertTrue(value.isFinite())
                assertTrue(abs(value) <= 1.00001f)
            }
        }
        synth.allNotesOff()
        synth.renderStereo(FloatArray(9_600 * 2), 9_600)
        assertEquals(0, synth.activeVoiceCount)
    }

    @Test fun bypassFadeEndingOnBlockBoundaryCannotResurrectOldDelayEchoes() {
        // At 8kHz the bypass slew is exactly 80 frames. If the audio worker
        // skips idle rack processing immediately afterward, the final fade
        // frame must have discarded delay history before the next enable.
        val fx = com.orbital.synth.core.dsp.processing.ModularFxChain(8_000)
        val delay = FxModuleConfig(FxModuleType.DELAY,
            enabled = true, wet = 1f, depth = 0f, rate = 0f)
        val stereo = FloatArray(2)
        fx.setSlot(0, delay)
        repeat(400) { frame ->
            fx.process(if (frame == 300) 1f else 0f, 0f, stereo)
        }
        fx.setSlot(0, delay.copy(enabled = false))
        repeat(80) { fx.process(0f, 0f, stereo) }
        assertFalse("Exactly the final fade sample should retire processing",
            fx.needsProcessing)

        // Intentionally do not call process() for any idle block, matching
        // OrbitalSynth's no-voice/no-FX CPU fast path.
        fx.setSlot(0, delay)
        repeat(300) {
            fx.process(0f, 0f, stereo)
            assertEquals("A reenabled delay cannot replay old notes", 0f, stereo[0], 0f)
            assertEquals(0f, stereo[1], 0f)
        }
    }

    @Test fun modulationGestureAndFxRackShapeTheSameLiveVoiceWithoutLosingEitherState() {
        // A pitch route published by the modulation editor and a new FX
        // chain slot must share one live OrbitalSynth bus, not two demo paths.
        val unmodulated = clearTone()
        val routed = clearTone()
        for (synth in listOf(unmodulated, routed)) {
            synth.setFxSlot(0, FxModuleType.SATURATION, true, 0.85f, 0.7f)
            synth.setFxSlot(1, FxModuleType.TREMOLO, true, 0.35f, 0.5f, 0.7f)
        }
        routed.setModulationRoute(
            0, com.orbital.synth.core.dsp.processing.ModSource.TOUCH_X,
            com.orbital.synth.core.dsp.processing.ModDestination.PITCH, 0.45f,
        )
        routed.setModulationSource(
            com.orbital.synth.core.dsp.processing.ModSource.TOUCH_X, 0.9f,
        )
        unmodulated.noteOn(69)
        routed.noteOn(69)
        val baseline = FloatArray(4_096 * 2)
        val affected = FloatArray(baseline.size)
        unmodulated.renderStereo(baseline, 4_096)
        routed.renderStereo(affected, 4_096)
        var audibleDifference = 0.0
        for (i in baseline.indices) {
            audibleDifference += abs((baseline[i] - affected[i]).toDouble())
            assertTrue(affected[i].isFinite())
        }
        assertTrue("Touch modulation must affect the real FX-processed synth voice",
            audibleDifference > 10.0)
        assertEquals(
            com.orbital.synth.core.dsp.processing.ModDestination.PITCH,
            routed.modulationRoute(0).destination,
        )
        routed.moveFxSlot(0, 1)
        routed.renderStereo(FloatArray(128), 64)
        assertEquals(FxModuleType.SATURATION, routed.fxSlot(1).type)
        assertTrue("FX reorder cannot clear a live modulation route",
            routed.modulationRoute(0).enabled)
    }

    private fun clearTone(): OrbitalSynth = OrbitalSynth().apply {
        setParameter(ParameterId.WAVE, 0f)
        setParameter(ParameterId.MORPH, 0f)
        setParameter(ParameterId.CUTOFF, 1f)
        setParameter(ParameterId.RESONANCE, 0f)
        setParameter(ParameterId.MOVEMENT, 0f)
        setParameter(ParameterId.FX_AMOUNT, 0f)
        setParameter(ParameterId.ATTACK, 0f)
        setParameter(ParameterId.RELEASE, 0f)
    }
}
