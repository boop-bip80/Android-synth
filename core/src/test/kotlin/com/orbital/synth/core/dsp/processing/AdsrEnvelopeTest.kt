package com.orbital.synth.core.dsp.processing

import org.junit.Assert.*
import org.junit.Test

class AdsrEnvelopeTest {
    @Test fun envelopeHitsEndpointsAtConfiguredSampleCounts() {
        val env = AdsrEnvelope(48_000)
        env.configure(0.001f, 0.002f, 0.3f, 0.003f)
        env.noteOn()
        repeat(47) { assertTrue(env.next() in 0f..1f) }
        assertEquals(1f, env.next(), 0.0001f)
        assertEquals(EnvelopeStage.DECAY, env.stage)
        repeat(96) { env.next() }
        assertEquals(EnvelopeStage.SUSTAIN, env.stage)
        assertEquals(0.3f, env.next(), 0.0001f)
        env.noteOff()
        repeat(144) { env.next() }
        assertEquals(EnvelopeStage.IDLE, env.stage)
        assertFalse(env.isActive)
        assertEquals(0f, env.next(), 0f)
    }

    @Test fun zeroDurationsAreInstantAndLegatoDoesNotResetLevel() {
        val env = AdsrEnvelope(48_000)
        env.configure(0f, 0f, 0.5f, 0f)
        env.noteOn()
        assertEquals(0.5f, env.next(), 0f)
        env.noteOn(retrigger = false)
        assertEquals(0.5f, env.level, 0f)
        env.noteOff()
        assertEquals(0f, env.level, 0f)
        assertFalse(env.isActive)
    }

    @Test fun earlyNoteOffReleasesFromCurrentLevel() {
        val env = AdsrEnvelope(48_000)
        env.configure(0.1f, 0.1f, 0.5f, 0.001f)
        env.noteOn()
        repeat(48) { env.next() }
        val levelAtRelease = env.level
        env.noteOff()
        assertTrue(env.next() < levelAtRelease)
        repeat(47) { env.next() }
        assertEquals(EnvelopeStage.IDLE, env.stage)
    }

    @Test fun changingAttackTimeRetainsCurrentLevelAndRetimesRemainingSegment() {
        val env = AdsrEnvelope(8_000)
        env.configure(0.01f, 0f, 1f, 0.01f) // 80 attack frames.
        env.noteOn()
        repeat(40) { env.next() }
        val midpoint = env.level
        assertEquals(0.5f, midpoint, 1e-5f)

        // Double attack time with half the original segment remaining: 80 new frames.
        env.configure(0.02f, 0f, 1f, 0.01f)
        assertEquals(midpoint, env.level, 0f)
        assertTrue(env.next() > midpoint)
        repeat(78) { env.next() }
        assertEquals(EnvelopeStage.ATTACK, env.stage)
        env.next()
        assertEquals(EnvelopeStage.SUSTAIN, env.stage)
        assertEquals(1f, env.level, 0f)
    }

    @Test fun sustainAutomationInDecayAndReleaseRetimingRemainContinuous() {
        val env = AdsrEnvelope(8_000)
        env.configure(0f, 0.01f, 0.5f, 0.02f)
        env.noteOn()
        repeat(20) { env.next() }
        val decayLevel = env.level
        env.configure(0f, 0.01f, 0.2f, 0.02f)
        assertEquals("Decay target change must not jump", decayLevel, env.level, 0f)
        assertTrue(env.next() < decayLevel)
        repeat(59) { env.next() }
        assertEquals(EnvelopeStage.SUSTAIN, env.stage)
        assertEquals(0.2f, env.level, 1e-5f)

        env.noteOff()
        repeat(40) { env.next() } // 120 of original 160 release frames left.
        val releaseLevel = env.level
        env.configure(0f, 0.01f, 0.2f, 0.01f)
        assertEquals("Release time change must not jump", releaseLevel, env.level, 0f)
        assertTrue(env.next() < releaseLevel)
        repeat(59) { env.next() }
        assertEquals(EnvelopeStage.IDLE, env.stage)
        assertEquals(0f, env.level, 0f)
    }
}
