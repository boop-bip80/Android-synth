package com.orbital.synth.core.dsp.processing

import com.orbital.synth.core.preset.FactoryPatches
import com.orbital.synth.core.preset.PatchCodec
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/** Deterministic offline validation of the actual six-slot audio FX rack. */
class StereoCreativeFxTest {
    private fun input(frame: Int): Float =
        (0.56 * sin(frame * 0.073) + 0.19 * sin(frame * 0.017)).toFloat()

    @Test fun phaserProducesRealStereoSweepWithoutNonfiniteOrUnboundedSamples() {
        val phaser = StereoPhaser(8_000)
        phaser.configure(rateHz = 2.7f, stereoPhase = 1f)
        val out = FloatArray(2)
        var stereoDifference = 0.0
        var inputDifference = 0.0
        repeat(12_000) { frame ->
            val dry = input(frame)
            phaser.process(dry, dry, 0.9f, out)
            if (frame > 500) {
                stereoDifference += abs((out[0] - out[1]).toDouble())
                inputDifference += abs((dry - out[0]).toDouble())
            }
            for (v in out) assertTrue(v.isFinite() && abs(v) <= 8f)
        }
        assertTrue("L/R phaser stages must have independent audible modulation",
            stereoDifference > 10.0)
        assertTrue("The four allpass stages must really process spectral phase",
            inputDifference > 10.0)
    }

    @Test fun phaserIsBitIdenticalAcrossBlocksAndResetRemovesOldFeedback() {
        val one = StereoPhaser(48_000).apply { configure(4.25f, .72f) }
        val chunked = StereoPhaser(48_000).apply { configure(4.25f, .72f) }
        val a = FloatArray(2)
        val b = FloatArray(2)
        repeat(6_144) { frame ->
            val dry = input(frame)
            one.process(dry, -dry, .86f, a)
            chunked.process(dry, -dry, .86f, b)
            assertArrayEquals(a, b, 0f)
        }
        one.resetForBypass()
        repeat(256) {
            one.process(0f, 0f, 1f, a)
            assertEquals(0f, a[0], 0f)
            assertEquals(0f, a[1], 0f)
        }
    }

    @Test fun bitcrusherClockIsRealHertzAcrossEightAndFortyEightKilohertz() {
        for (sampleRate in intArrayOf(8_000, 48_000)) {
            val crush = StereoBitcrusher(sampleRate)
            crush.configure(rateControl = 0f, stereo = .5f) // 250 captures/s
            val out = FloatArray(2)
            var changes = 0
            var previous = Float.NaN
            repeat(sampleRate) { frame ->
                val ramp = frame.toFloat() / sampleRate
                crush.process(ramp, ramp, 0f, out)
                if (frame > 0 && out[0] != previous) changes++
                previous = out[0]
                assertTrue(out[0].isFinite() && out[0] in -1f..1f)
            }
            assertTrue("250 Hz sample hold must not scale with host rate $sampleRate: $changes",
                changes in 230..270)
        }
    }

    @Test fun crusherQuantizesHardAtFourBitsAndResetDropsHeldAudio() {
        val crusher = StereoBitcrusher(48_000)
        crusher.configure(rateControl = 1f, stereo = .5f)
        val out = FloatArray(2)
        repeat(400) { crusher.process(.13f, -.13f, 1f, out) }
        assertEquals(.125f, out[0], 0f)
        assertEquals(-.125f, out[1], 0f)
        crusher.resetForBypass()
        crusher.process(0f, 0f, 1f, out)
        assertEquals(0f, out[0], 0f)
        assertEquals(0f, out[1], 0f)
        repeat(20) { crusher.process(.13f, -.13f, 0f, out) }
        assertTrue("Sixteen-bit mode should retain substantially finer resolution",
            abs(out[0] - .13f) < .001f)
    }

    @Test fun newEffectsAreActuallyAudibleWithinTheRealOrderedFxChain() {
        for (type in listOf(
            FxModuleType.REVERB, FxModuleType.PHASER, FxModuleType.BITCRUSH,
        )) {
            val wet = ModularFxChain(8_000)
            val dry = ModularFxChain(8_000)
            wet.setSlot(0, FxModuleConfig(
                type, enabled = true, wet = 1f, depth = .8f, rate = .56f, stereo = .9f,
            ))
            val a = FloatArray(2)
            val b = FloatArray(2)
            var difference = 0.0
            repeat(9_600) { frame ->
                val x = input(frame)
                dry.process(x, -x, a)
                wet.process(x, -x, b)
                difference += abs((a[0] - b[0]).toDouble()) +
                    abs((a[1] - b[1]).toDouble())
                assertTrue(b[0].isFinite() && abs(b[0]) <= 1f)
                assertTrue(b[1].isFinite() && abs(b[1]) <= 1f)
            }
            assertTrue("Enabled $type must audibly change live FX bus PCM",
                difference > 10.0)
        }
    }

    @Test fun allThreeNewEffectsAreSampleIdenticalAcrossRenderBufferSizes() {
        val whole = ModularFxChain(8_000)
        val partitioned = ModularFxChain(8_000)
        for (chain in listOf(whole, partitioned)) {
            chain.setSlot(0, FxModuleConfig(FxModuleType.REVERB, true, .55f, .7f, .65f, .8f))
            chain.setSlot(1, FxModuleConfig(FxModuleType.PHASER, true, .7f, .75f, .8f, .6f))
            chain.setSlot(2, FxModuleConfig(FxModuleType.BITCRUSH, true, .5f, .85f, .3f, .5f))
        }
        val frames = 4_096
        val source = FloatArray(frames * 2) { idx ->
            val signal = input(idx / 2)
            if (idx % 2 == 0) signal else -signal
        }
        val allAtOnce = source.copyOf()
        whole.processInterleaved(allAtOnce, frames)
        val by128 = FloatArray(source.size)
        val scratch = FloatArray(128 * 2)
        for (chunk in 0 until frames / 128) {
            System.arraycopy(source, chunk * scratch.size, scratch, 0, scratch.size)
            partitioned.processInterleaved(scratch, 128)
            System.arraycopy(scratch, 0, by128, chunk * scratch.size, scratch.size)
        }
        assertArrayEquals("DSP modulation, reverb and crusher must be frame-indexed",
            allAtOnce, by128, 0f)
    }

    @Test fun replacingNewModulesAndBypassingPreserveExactDrySignal() {
        val wet = ModularFxChain(8_000)
        val dry = ModularFxChain(8_000)
        val out = FloatArray(2)
        val baseline = FloatArray(2)
        for (type in listOf(
            FxModuleType.REVERB, FxModuleType.PHASER, FxModuleType.BITCRUSH,
            FxModuleType.REVERB,
        )) {
            wet.setSlot(0, FxModuleConfig(type, true, .8f, .9f, .6f, .7f))
            repeat(350) { frame ->
                val x = input(frame)
                wet.process(x, -x, out)
                assertTrue(out[0].isFinite() && abs(out[0]) <= 1f)
            }
        }
        wet.setSlot(0, FxModuleConfig())
        repeat(90) { wet.process(0f, 0f, out) } // >= 10ms bypass @8k
        assertFalse(wet.needsProcessing)
        repeat(2_048) { frame ->
            val x = input(frame)
            dry.process(x, -x, baseline)
            wet.process(x, -x, out)
            assertArrayEquals("Complete rack bypass must be bit-identical dry",
                baseline, out, 0f)
        }
    }

    @Test fun disablingReverbAtExactFadeBoundaryCannotResurrectOldTails() {
        val fx = ModularFxChain(8_000)
        val reverb = FxModuleConfig(
            FxModuleType.REVERB, true, 1f, 1f, .65f, 1f,
        )
        val out = FloatArray(2)
        fx.setSlot(0, reverb)
        repeat(4_000) { frame ->
            fx.process(if (frame == 500) 1f else 0f, 0f, out)
        }
        fx.setSlot(0, reverb.copy(enabled = false))
        repeat(80) { fx.process(0f, 0f, out) }
        assertFalse(fx.needsProcessing)
        // The host skips ALL idle blocks here (no callbacks to clear history).
        fx.setSlot(0, reverb)
        repeat(3_000) {
            fx.process(0f, 0f, out)
            assertEquals("No reverb from previous patch may return", 0f, out[0], 0f)
            assertEquals(0f, out[1], 0f)
        }
    }

    @Test fun rapidTripleTypeAutomationRemainsFiniteBoundedAndDeterministic() {
        val one = ModularFxChain(8_000)
        val two = ModularFxChain(8_000)
        val types = listOf(FxModuleType.REVERB, FxModuleType.PHASER, FxModuleType.BITCRUSH)
        val outA = FloatArray(2)
        val outB = FloatArray(2)
        repeat(7_680) { frame ->
            if (frame % 240 == 0) {
                val step = frame / 240
                val config = FxModuleConfig(
                    types[step % types.size], true,
                    wet = (step % 9) / 9f,
                    depth = (step % 7) / 7f,
                    rate = (step % 11) / 11f,
                    stereo = (step % 5) / 5f,
                )
                one.setSlot(0, config)
                two.setSlot(0, config)
            }
            val x = input(frame)
            one.process(x, -x, outA)
            two.process(x, -x, outB)
            assertArrayEquals(outA, outB, 0f)
            assertTrue(outA[0].isFinite() && abs(outA[0]) <= 1f)
            assertTrue(outA[1].isFinite() && abs(outA[1]) <= 1f)
        }
    }

    @Test fun patchV4StoresNewFxTypesByStableNamesWithoutDisturbingOldOrdinals() {
        assertEquals(0, FxModuleType.SATURATION.ordinal)
        assertEquals(1, FxModuleType.TREMOLO.ordinal)
        assertEquals(2, FxModuleType.CHORUS.ordinal)
        assertEquals(3, FxModuleType.DELAY.ordinal)
        val fx = listOf(
            FxModuleConfig(FxModuleType.REVERB, true, .5f, .8f, .3f, .9f),
            FxModuleConfig(FxModuleType.PHASER, false, .9f, .5f, .2f, .5f),
            FxModuleConfig(FxModuleType.BITCRUSH, true, .7f, .95f, .1f, .2f),
        )
        val patch = FactoryPatches.DEFAULT.copy(fxSlots = fx)
        val encoded = PatchCodec.encode(patch)
        assertTrue(encoded.startsWith("ORBITAL-PATCH/4\n"))
        assertTrue(encoded.contains("fx.0=1,REVERB,"))
        assertTrue(encoded.contains("fx.1=0,PHASER,"))
        assertTrue(encoded.contains("fx.2=1,BITCRUSH,"))
        assertEquals(patch, PatchCodec.decode(encoded))
    }
}
