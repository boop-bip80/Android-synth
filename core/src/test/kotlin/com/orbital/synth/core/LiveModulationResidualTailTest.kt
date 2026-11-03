package com.orbital.synth.core

import com.orbital.synth.core.dsp.processing.ModCurve
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Real rendered buses + stereo PCM proofs for the bounded last-global-route
 * release. The 8/12 ms values are exponential time constants; completion is
 * epsilon-gated with an independent <=120 ms sample-count upper bound.
 */
class LiveModulationResidualTailTest {
    private val globals = arrayOf(
        ModDestination.MORPH, ModDestination.PITCH,
        ModDestination.CUTOFF, ModDestination.RESONANCE,
        ModDestination.AMP, ModDestination.PAN,
    )

    private fun host() = LiveModulationHost(8_000)
    private fun sync(m: LiveModulationHost) = m.syncBlock(.5f, .45f, .35f)

    private fun normalizedOutput(m: LiveModulationHost, d: ModDestination): Float =
        when (d) {
            ModDestination.MORPH -> m.morph
            ModDestination.PITCH -> m.pitchSemitones / 12f + .5f
            ModDestination.CUTOFF -> m.cutoff
            ModDestination.RESONANCE -> m.resonance
            ModDestination.AMP -> m.ampGain / 2f
            ModDestination.PAN -> m.panOffset / 2f + .5f
            else -> error("Not a global DSP destination: " + d)
        }

    private fun dry(d: ModDestination) = when (d) {
        ModDestination.CUTOFF -> .45f
        ModDestination.RESONANCE -> .35f
        else -> .5f
    }

    @Test fun allSixGlobalBusesDeclickWithoutAnImmediateHardSnap() {
        for (d in globals) {
            val m = host()
            m.setSource(ModSource.TOUCH_X, 1f)
            m.setRoute(0, ModSource.TOUCH_X, d, .3f, ModCurve.LINEAR)
            assertTrue(sync(m))
            repeat(1_200) { m.tick() }
            val before = normalizedOutput(m, d)
            assertTrue("Expected a truly audible " + d + " signal",
                abs(before - dry(d)) > .24f)

            // Keep editor metadata enabled but remove audible depth.
            m.setRoute(0, ModSource.TOUCH_X, d, 0f, ModCurve.LINEAR)
            assertTrue("A just-audible " + d + " must retain its bounded fade", sync(m))
            assertEquals(0f, m.route(0).depth, 0f)
            assertTrue(m.route(0).enabled)
            if (d == ModDestination.PITCH) assertTrue(m.pitchRouteActive)
            if (d == ModDestination.CUTOFF || d == ModDestination.RESONANCE) {
                assertTrue(m.filterRouteActive)
            }
            if (d == ModDestination.AMP) assertTrue(m.ampRouteActive)
            if (d == ModDestination.PAN) assertTrue(m.panRouteActive)

            m.tick()
            val afterOneSample = normalizedOutput(m, d)
            val firstStep = abs(afterOneSample - before)
            assertTrue("First " + d + " release sample must be a real glide, not hard snap",
                firstStep > 0f && firstStep < abs(before - dry(d)) * .04f)
            assertTrue("First " + d + " release must retain most of old signal",
                abs(afterOneSample - dry(d)) > abs(before - dry(d)) * .9f)

            var tailFrames = 1
            while (sync(m) && tailFrames < 1_100) {
                m.tick()
                tailFrames++
            }
            assertTrue("Must perform a finite real slew for " + d,
                tailFrames in 10..960)
            assertFalse("No 48kHz work after " + d + " tail retires", sync(m))
            assertFalse(m.active)
            assertFalse(m.pitchRouteActive)
            assertFalse(m.filterRouteActive)
            assertFalse(m.ampRouteActive)
            assertFalse(m.panRouteActive)
            assertTrue("Audible release must settle to dry baseline for " + d,
                abs(normalizedOutput(m, d) - dry(d)) <= .0002f)
        }
    }

    @Test fun everyDepartedGlobalBusStillDeclicksWithAnotherLiveRoute() {
        for (d in globals) {
            val m = host()
            m.setSource(ModSource.TOUCH_X, 1f)
            val otherDestination = when (d) {
                ModDestination.MORPH, ModDestination.AMP, ModDestination.PAN ->
                    ModDestination.OSC1_GAIN
                else -> ModDestination.MORPH
            }
            val otherSource = if (d == ModDestination.CUTOFF ||
                d == ModDestination.RESONANCE) ModSource.LFO1 else ModSource.TOUCH_X
            m.setRoute(0, ModSource.TOUCH_X, d, .3f, ModCurve.LINEAR)
            m.setRoute(1, otherSource, otherDestination, .15f, ModCurve.LINEAR)
            assertTrue(sync(m))
            repeat(1_000) { m.tick() }
            val before = normalizedOutput(m, d)
            assertTrue(abs(before - dry(d)) > .23f)

            m.setRoute(0, ModSource.TOUCH_X, d, 0f, ModCurve.LINEAR)
            assertTrue("The unrelated route must remain connected", sync(m))
            if (d == ModDestination.PITCH) {
                assertTrue("Pitch multiplier must remain audio-rate during residual", m.pitchRouteActive)
            }
            if (d == ModDestination.CUTOFF || d == ModDestination.RESONANCE) {
                assertTrue("SVF control must continue for residual filter movement", m.filterRouteActive)
            }
            if (d == ModDestination.AMP) {
                assertTrue("Mixer may not bypass a still-slewing AMP", m.ampRouteActive)
            }
            if (d == ModDestination.PAN) {
                assertTrue("Mixer may not bypass a still-slewing PAN", m.panRouteActive)
            }

            m.tick()
            val first = normalizedOutput(m, d)
            assertTrue("Live second route must not hard-snap removed " + d,
                abs(first - before) < abs(before - dry(d)) * .04f)
            repeat(1_100) {
                assertTrue("Unrelated live cable stays active during/after residual", sync(m))
                m.tick()
            }
            assertTrue("Departed bus must reach neutral while other cable continues " + d,
                abs(normalizedOutput(m, d) - dry(d)) < .0002f)
            if (d == ModDestination.PITCH) assertFalse(m.pitchRouteActive)
            if (d == ModDestination.CUTOFF || d == ModDestination.RESONANCE) {
                assertFalse(m.filterRouteActive)
            }
            if (d == ModDestination.AMP) assertFalse(m.ampRouteActive)
            if (d == ModDestination.PAN) assertFalse(m.panRouteActive)
            assertTrue(m.active)
        }
    }

    @Test fun quickOffOnReversalResumesFromExistingSlewWithoutStepDiscontinuity() {
        val m = host()
        m.setSource(ModSource.TOUCH_X, 1f)
        m.setRoute(0, ModSource.TOUCH_X, ModDestination.AMP, .4f, ModCurve.LINEAR)
        assertTrue(sync(m))
        repeat(700) { m.tick() }
        assertTrue(m.ampGain > 1.7f)

        m.setRoute(0, ModSource.TOUCH_X, ModDestination.AMP, 0f, ModCurve.LINEAR)
        assertTrue(sync(m))
        repeat(50) { m.tick() }
        val duringFade = m.ampGain
        assertTrue(duringFade > 1.2f && duringFade < 1.8f)

        m.setSource(ModSource.TOUCH_X, -1f)
        m.setRoute(0, ModSource.TOUCH_X, ModDestination.AMP, .4f, ModCurve.LINEAR)
        assertTrue(sync(m))
        val beforeReturn = m.ampGain
        m.tick()
        val afterReturn = m.ampGain
        assertTrue("Reverse automation must continue smoothly from current gain",
            afterReturn < beforeReturn && beforeReturn - afterReturn < .05f)
        repeat(900) { m.tick() }
        assertTrue("New negative route must become fully effective", m.ampGain < .25f)

        // Only the last *effective* removal begins a bounded tail. Zero-depth
        // metadata edits while tailing must not extend it indefinitely.
        m.setRoute(0, ModSource.TOUCH_X, ModDestination.AMP, 0f, ModCurve.LINEAR)
        assertTrue(sync(m))
        var activeFrames = 0
        repeat(1_500) { frame ->
            if (frame % 32 == 0) {
                m.setRoute(0, ModSource.TOUCH_X, ModDestination.AMP,
                    0f, if (frame % 64 == 0) ModCurve.SOFT else ModCurve.LINEAR)
            }
            if (sync(m)) {
                m.tick()
                activeFrames++
            } else m.tickStepOnly()
        }
        assertTrue("No inert metadata flood can re-arm the residual sample cap",
            activeFrames in 1..960)
        assertFalse(sync(m))
    }

    @Test fun warmedZeroDepthRouteGlidesInRatherThanStartingAtFullModulatedValue() {
        val m = host()
        m.setSource(ModSource.TOUCH_X, 1f)
        m.setRoute(0, ModSource.TOUCH_X, ModDestination.AMP, 0f, ModCurve.LINEAR)
        assertFalse(sync(m))
        repeat(400) { m.tickStepOnly() }
        m.setRoute(0, ModSource.TOUCH_X, ModDestination.AMP, .45f, ModCurve.LINEAR)
        assertTrue(sync(m))
        m.tick()
        assertTrue("Dry->active modulation should be a finite 8ms onset glide",
            m.ampGain > 1f && m.ampGain < 1.05f)
        repeat(700) { m.tick() }
        assertTrue(m.ampGain > 1.85f)
    }

    @Test fun inactiveRoutesDoNotCreateFakeTailsAndSilenceCancelsRealTail() {
        val m = host()
        m.setSource(ModSource.TOUCH_X, 0f)
        m.setRoute(0, ModSource.TOUCH_X, ModDestination.MORPH, .8f, ModCurve.LINEAR)
        assertTrue(sync(m))
        repeat(400) { m.tick() } // nonzero route, but source is exactly zero
        m.setRoute(0, ModSource.TOUCH_X, ModDestination.MORPH, 0f, ModCurve.LINEAR)
        assertFalse("An inaudible global bus needs no declick processing", sync(m))

        val sounding = host()
        sounding.setSource(ModSource.TOUCH_X, 1f)
        sounding.setRoute(0, ModSource.TOUCH_X, ModDestination.PAN, .4f, ModCurve.LINEAR)
        assertTrue(sync(sounding))
        repeat(700) { sounding.tick() }
        sounding.setRoute(0, ModSource.TOUCH_X, ModDestination.PAN, 0f, ModCurve.LINEAR)
        assertTrue(sync(sounding))
        sounding.advanceSilence(512)
        assertFalse("No voices => no stale active release or pan tail", sync(sounding))
        assertFalse(sounding.panRouteActive)
    }

    @Test fun sixGlobalReleasePcmIsConsistentAcross192And512FrameCallbacks() {
        for (d in globals) {
            val small = soundingSynth(d)
            val large = soundingSynth(d)
            // Stress removal of a bus while another effective cable keeps
            // matrix/voices live; PITCH, filter, AMP and PAN flags must not
            // bypass their residual output just because one cable departed.
            val other = if (d == ModDestination.MORPH || d == ModDestination.AMP ||
                d == ModDestination.PAN) ModDestination.OSC1_GAIN else ModDestination.MORPH
            for (machine in arrayOf(small, large)) {
                machine.setModulationRoute(1, ModSource.TOUCH_X, other, .12f)
            }
            small.noteOn(61)
            large.noteOn(61)
            val beforeSmall = renderInChunks(small, 1_536, 192)
            val beforeLarge = renderInChunks(large, 1_536, 512)
            assertClosePcm("Before " + d + " depth edit", beforeSmall, beforeLarge, .002f)

            small.setModulationRoute(0, ModSource.TOUCH_X, d, 0f)
            large.setModulationRoute(0, ModSource.TOUCH_X, d, 0f)
            val afterSmall = renderInChunks(small, 3_072, 192)
            val afterLarge = renderInChunks(large, 3_072, 512)
            assertClosePcm("During/after " + d + " declick", afterSmall, afterLarge, .003f)
            assertTrue("PCM must stay finite after " + d,
                afterSmall.all { it.isFinite() } && afterLarge.all { it.isFinite() })
        }
    }

    @Test fun realAmpPcmDepartsProgressivelyFromStillConnectedReference() {
        val constant = soundingSynth(ModDestination.AMP)
        val removed = soundingSynth(ModDestination.AMP)
        constant.noteOn(64)
        removed.noteOn(64)
        val beforeA = renderInChunks(constant, 1_536, 192)
        val beforeB = renderInChunks(removed, 1_536, 192)
        assertRawParity(beforeA, beforeB)
        removed.setModulationRoute(0, ModSource.TOUCH_X, ModDestination.AMP, 0f)
        val held = renderInChunks(constant, 512, 192, allowRemainder = true)
        val freed = renderInChunks(removed, 512, 192, allowRemainder = true)

        fun discrepancy(start: Int, end: Int): Double {
            var total = 0.0
            for (frame in start until end) {
                total += abs((held[frame * 2] - freed[frame * 2]).toDouble())
                total += abs((held[frame * 2 + 1] - freed[frame * 2 + 1]).toDouble())
            }
            return total / (end - start)
        }
        val first = discrepancy(0, 8)
        val later = discrepancy(220, 300)
        assertTrue("Audible later AMP release must differ from still-routed PCM", later > .005)
        assertTrue("First eight stereo frames must not jump straight to dry gain",
            first < later * .25)
    }

    @Test fun oversizedSingleRenderCallbackCannotKeepExpiredTailDspAlive() {
        val m = host()
        m.setSource(ModSource.TOUCH_X, 1f)
        m.setRoute(0, ModSource.TOUCH_X, ModDestination.PITCH, .4f, ModCurve.LINEAR)
        m.setRoute(1, ModSource.TOUCH_X, ModDestination.AMP, .4f, ModCurve.LINEAR)
        assertTrue(sync(m))
        repeat(700) { m.tick() }
        assertTrue(abs(m.pitchSemitones) > 1f && m.ampGain > 1.4f)
        m.setRoute(0, ModSource.TOUCH_X, ModDestination.PITCH, 0f, ModCurve.LINEAR)
        m.setRoute(1, ModSource.TOUCH_X, ModDestination.AMP, 0f, ModCurve.LINEAR)
        assertTrue(sync(m))
        // OrbitalSynth captures modulationEnabled once per callback; deliberately
        // never sync again here. tick() must switch to its cheap neutral branch
        // *inside* a very large block after the 120 ms maximum sample tail.
        repeat(16_000) { m.tick() }
        assertEquals(0f, m.pitchSemitones, 0f)
        assertEquals(1f, m.ampGain, 0f)
        assertFalse(sync(m))
        assertFalse(m.pitchRouteActive)
        assertFalse(m.ampRouteActive)
    }

    @Test fun untouchedDrySynthRemainsRawPcmIdenticalWithOnlyDormantRoutes() {
        val dry = configuredSynth()
        val inert = configuredSynth()
        for (slot in 0..63) inert.setModulationRoute(
            slot, ModSource.TOUCH_X,
            if (slot % 2 == 0) ModDestination.PAN else ModDestination.FOLD,
            if (slot % 2 == 0) 0f else .85f,
        )
        inert.setModulationSource(ModSource.TOUCH_X, 1f)
        dry.noteOn(61)
        inert.noteOn(61)
        assertRawParity(renderInChunks(dry, 3_072, 192),
            renderInChunks(inert, 3_072, 192))
    }

    private fun configuredSynth() = OrbitalSynth(sampleRate = 8_000, maxVoices = 4).apply {
        setParameter(ParameterId.WAVE, .43f)
        setParameter(ParameterId.MORPH, .15f)
        setParameter(ParameterId.CUTOFF, .95f)
        setParameter(ParameterId.RESONANCE, .1f)
        setParameter(ParameterId.MOVEMENT, 0f)
        setParameter(ParameterId.LFO_DEPTH, 0f)
        setParameter(ParameterId.FX_AMOUNT, 0f)
        setParameter(ParameterId.ATTACK, 0f)
        setParameter(ParameterId.SUSTAIN, 1f)
        setParameter(ParameterId.RELEASE, 0f)
    }

    private fun soundingSynth(d: ModDestination) = configuredSynth().apply {
        setModulationRoute(0, ModSource.TOUCH_X, d, .3f)
        setModulationSource(ModSource.TOUCH_X, 1f)
    }

    private fun renderInChunks(
        synth: OrbitalSynth, frames: Int, chunk: Int, allowRemainder: Boolean = false,
    ): FloatArray {
        require(allowRemainder || frames % chunk == 0)
        val output = FloatArray(frames * 2)
        val buffer = FloatArray(chunk * 2)
        var rendered = 0
        while (rendered < frames) {
            val take = minOf(chunk, frames - rendered)
            synth.renderStereo(buffer, take)
            System.arraycopy(buffer, 0, output, rendered * 2, take * 2)
            rendered += take
        }
        return output
    }

    private fun assertClosePcm(label: String, first: FloatArray, second: FloatArray, max: Float) {
        assertEquals(first.size, second.size)
        var biggest = 0f
        for (i in first.indices) {
            assertTrue(label + ": nonfinite sample " + i, first[i].isFinite() && second[i].isFinite())
            biggest = maxOf(biggest, abs(first[i] - second[i]))
        }
        assertTrue(label + " maximum stereo difference=" + biggest, biggest < max)
    }

    private fun assertRawParity(a: FloatArray, b: FloatArray) {
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals("Raw PCM mismatch at stereo sample " + i,
                java.lang.Float.floatToRawIntBits(a[i]),
                java.lang.Float.floatToRawIntBits(b[i]))
        }
    }
}
