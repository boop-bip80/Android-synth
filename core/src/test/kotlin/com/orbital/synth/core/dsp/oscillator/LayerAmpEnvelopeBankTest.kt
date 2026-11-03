package com.orbital.synth.core.dsp.oscillator

import com.orbital.synth.core.LayerAmpEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LayerAmpEnvelopeBankTest {

    @Test fun bypassCrossfadeSnapsToExactZeroAndOneAt48And192k() {
        for (sampleRate in intArrayOf(48_000, 192_000)) {
            val bank = LayerAmpEnvelopeBank(sampleRate)
            val bypass = LayerAmpEnvelope()
            val shaped = LayerAmpEnvelope(
                enabled = true, attack = 1f, decay = .8f, sustain = .2f,
            )
            bank.configure(2, bypass)
            bank.primeSilentSlot(2)
            bank.noteOn()
            bank.configure(2, shaped)
            val first = bank.nextGain(2)
            assertTrue("Enabling the VCA must de-click at $sampleRate",
                first > .995f)
            repeat(sampleRate / 4) { bank.nextGain(2) }

            // A fixed 1e-5 threshold stalls at 0.99998856/0.99995422
            // at 48/192k and never reaches exact full VCA authority.
            val field = LayerAmpEnvelopeBank::class.java.getDeclaredField("bypassMix")
            field.isAccessible = true
            val mix = field.get(bank) as FloatArray
            assertEquals("VCA full-enable never reached exact unity at $sampleRate",
                1f, mix[2], 0f)

            val beforeDisable = bank.nextGain(2)
            bank.configure(2, shaped.copy(enabled = false))
            val afterDisable = bank.nextGain(2)
            assertTrue("Bypass must not jump on the first disabled frame",
                abs(afterDisable - beforeDisable) < .01f)
            repeat(sampleRate / 4) { bank.nextGain(2) }
            assertEquals("Bypass must settle exactly to zero at $sampleRate",
                0f, mix[2], 0f)
            assertFalse("Bypassed DSP lane must fully retire", bank.isEngaged(2))
            assertEquals(1f, bank.nextGain(2), 0f)
        }
    }

    @Test fun liveSustainIncreaseToOneReachesExactFullAmplitudeAtBothRates() {
        for (sampleRate in intArrayOf(48_000, 192_000)) {
            val bank = LayerAmpEnvelopeBank(sampleRate)
            val config = LayerAmpEnvelope(
                enabled = true, attack = 0f, decay = .02f, sustain = .2f,
            )
            bank.configure(0, config)
            bank.primeSilentSlot(0)
            bank.noteOn()
            repeat(sampleRate / 2) { bank.nextGain(0) }
            assertEquals(.2f, bank.nextGain(0), .01f)
            bank.configure(0, config.copy(sustain = 1f))
            assertTrue("A 100% sustain edit must slew, never jump",
                bank.nextGain(0) < .21f)
            repeat(sampleRate / 4) { bank.nextGain(0) }
            assertEquals("No floating sustain plateau below unity at $sampleRate",
                1f, bank.nextGain(0), 0f)
        }
    }

    @Test fun threeSecondDecayNearUnityHasSampleRateAccurateAmplitude() {
        for (sampleRate in intArrayOf(48_000, 192_000)) {
            val bank = LayerAmpEnvelopeBank(sampleRate)
            bank.configure(0, LayerAmpEnvelope(
                enabled = true, attack = 0f, decay = 1f,
                sustain = .99f, release = 0f,
            ))
            bank.primeSilentSlot(0)
            bank.noteOn()
            repeat(sampleRate) { bank.nextGain(0) }
            val afterOneSecond = bank.nextGain(0)
            assertTrue("48/192k DECAY must progress accurately; " +
                "Float32 per-frame step can stall at 1: $afterOneSecond",
                afterOneSecond >= .9965f && afterOneSecond <= .9969f)
            repeat(sampleRate * 3) { bank.nextGain(0) }
            assertEquals(.99f, bank.nextGain(0), .0001f)
        }
    }

    @Test fun disabledBankReturnsExactUnityThroughGatesAndReset() {
        val bank = LayerAmpEnvelopeBank(48_000)
        bank.configure(0, LayerAmpEnvelope())
        bank.primeSilentSlot(0)
        bank.noteOn()
        repeat(20_000) { assertEquals(1f, bank.nextGain(0), 0f) }
        bank.noteOff()
        repeat(20_000) { assertEquals(1f, bank.nextGain(0), 0f) }
        bank.hardStop()
        assertFalse(bank.isEngaged(0))
        assertEquals(1f, bank.nextGain(0), 0f)
    }

    @Test fun independentlyGatedAttackDecaySustainAndReleaseHaveRealSampleTimelines() {
        val bank = LayerAmpEnvelopeBank(48_000)
        val first = LayerAmpEnvelope(
            enabled = true, attack = .5f, decay = .4f, sustain = .3f, release = .2f,
        )
        val second = LayerAmpEnvelope(
            enabled = true, attack = 0f, decay = 0f, sustain = 1f, release = 0f,
        )
        bank.configure(0, first)
        bank.configure(1, second)
        bank.primeSilentSlot(0)
        bank.primeSilentSlot(1)
        bank.noteOn()

        val firstSample = bank.nextGain(0)
        val secondSample = bank.nextGain(1)
        assertTrue("Long layer attack starts quieter than instant layer", firstSample < .002f)
        assertTrue(secondSample > firstSample)
        repeat(12_000) {
            bank.nextGain(0)
            bank.nextGain(1)
        }
        val quarter = bank.nextGain(0)
        assertTrue("Long layer attack must move over actual samples: $quarter",
            quarter >= .20f && quarter <= .30f)
        assertEquals(1f, bank.nextGain(1), .00001f)
        repeat(90_000) { bank.nextGain(0) }
        val sustain = bank.nextGain(0)
        assertEquals(.3f, sustain, .002f)
        bank.noteOff()
        val firstRelease = bank.nextGain(0)
        assertTrue("Release must start continuously", abs(firstRelease - sustain) < .002f)
        repeat(16_000) { bank.nextGain(0) }
        assertTrue("Eventually the independently shaped lane must be silent",
            bank.nextGain(0) < .0001f)
        assertTrue("Per-layer release stays subordinate to the master voice envelope",
            first.release > 0f)
    }

    @Test fun liveEnableAndDisableNeverCreateInstantaneousGainJumps() {
        val bank = LayerAmpEnvelopeBank(48_000)
        bank.configure(3, LayerAmpEnvelope())
        bank.primeSilentSlot(3)
        bank.noteOn()
        repeat(200) { assertEquals(1f, bank.nextGain(3), 0f) }
        val shaped = LayerAmpEnvelope(
            enabled = true, attack = 1f, decay = 0f, sustain = .4f, release = .2f,
        )
        bank.configure(3, shaped)
        assertTrue("First sample after enable is de-clicked", bank.nextGain(3) > .995f)
        repeat(1_000) { bank.nextGain(3) }
        val beforeDisable = bank.nextGain(3)
        assertTrue("Active ADSR must have taken control", beforeDisable < .5f)
        bank.configure(3, shaped.copy(enabled = false))
        val afterDisable = bank.nextGain(3)
        assertTrue("Disabling an active VCA cannot jump directly to unity",
            abs(afterDisable - beforeDisable) < .01f)
        repeat(8_000) { bank.nextGain(3) }
        assertFalse(bank.isEngaged(3))
        assertEquals(1f, bank.nextGain(3), 0f)
    }

    @Test fun editedSustainAndRetriggeredNoteDoNotLeakPriorEnvelopeStage() {
        val bank = LayerAmpEnvelopeBank(48_000)
        val config = LayerAmpEnvelope(enabled = true, sustain = .1f, attack = 0f, decay = .01f)
        bank.configure(0, config)
        bank.primeSilentSlot(0)
        bank.noteOn()
        repeat(12_000) { bank.nextGain(0) }
        assertEquals(.1f, bank.nextGain(0), .02f)
        bank.configure(0, config.copy(sustain = .9f))
        val afterAutomation = bank.nextGain(0)
        assertTrue("Increasing sustain must slew without jumping", afterAutomation < .12f)
        repeat(6_000) { bank.nextGain(0) }
        assertEquals(.9f, bank.nextGain(0), .02f)
        bank.noteOff()
        repeat(1_000) { bank.nextGain(0) }
        bank.hardStop()
        bank.noteOn()
        assertTrue("A new/stealing note restarts the layer attack", bank.nextGain(0) < .1f)
    }

    @Test fun envelopeFrameUpdatesAreBitExactAcross192And512Blocks() {
        val one = LayerAmpEnvelopeBank(48_000)
        val chunks = LayerAmpEnvelopeBank(48_000)
        val config = LayerAmpEnvelope(
            enabled = true, attack = .07f, decay = .13f, sustain = .41f, release = .21f,
        )
        for (bank in arrayOf(one, chunks)) {
            bank.configure(4, config)
            bank.primeSilentSlot(4)
            bank.noteOn()
        }
        val count = 3_072
        val all = FloatArray(count)
        val pieces = FloatArray(count)
        for (frame in 0 until count) all[frame] = one.nextGain(4)
        for (chunk in 0 until 16) {
            for (frame in 0 until 192) pieces[chunk * 192 + frame] = chunks.nextGain(4)
        }
        for (i in 0 until count) {
            assertEquals("Frame $i must be byte-identical",
                java.lang.Float.floatToRawIntBits(all[i]),
                java.lang.Float.floatToRawIntBits(pieces[i]))
        }
        val wide = LayerAmpEnvelopeBank(48_000)
        wide.configure(4, config)
        wide.primeSilentSlot(4)
        wide.noteOn()
        for (chunk in 0 until 6) {
            for (frame in 0 until 512) {
                val i = chunk * 512 + frame
                assertEquals(java.lang.Float.floatToRawIntBits(all[i]),
                    java.lang.Float.floatToRawIntBits(wide.nextGain(4)))
            }
        }
    }

    @Test fun oneHundredTwentyEightEnvelopeLanesRemainFiniteUnderStress() {
        val banks = Array(16) { LayerAmpEnvelopeBank(48_000) }
        banks.forEachIndexed { voice, bank ->
            for (slot in 0 until 8) {
                bank.configure(slot, LayerAmpEnvelope(
                    enabled = true,
                    attack = (voice % 4) / 4f,
                    decay = slot / 8f,
                    sustain = (slot % 3) / 3f,
                    release = (voice % 5) / 5f,
                ))
                bank.primeSilentSlot(slot)
            }
            bank.noteOn()
        }
        repeat(4_096) { frame ->
            if (frame == 512) banks.forEach { it.noteOff() }
            if (frame == 2_048) banks.forEach { it.hardStop(); it.noteOn() }
            banks.forEach { bank ->
                for (slot in 0 until 8) {
                    val level = bank.nextGain(slot)
                    assertTrue(level.isFinite() && level in 0f..1.00001f)
                }
            }
        }
    }
}
