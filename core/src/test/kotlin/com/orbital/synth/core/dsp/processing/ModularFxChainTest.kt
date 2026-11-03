package com.orbital.synth.core.dsp.processing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class ModularFxChainTest {
    @Test fun sixDefaultOrFullyBypassedSlotsAreExactlyDry() {
        val fx = ModularFxChain(sampleRate = 8_000)
        assertEquals(6, fx.slotCount)
        val out = FloatArray(2)
        repeat(4_000) { i ->
            val l = sin(i * 0.057).toFloat() * 0.8f
            val r = sin(i * 0.021).toFloat() * 0.7f
            fx.process(l, r, out)
            assertEquals(l, out[0], 0f)
            assertEquals(r, out[1], 0f)
        }
        fx.setSlot(0, FxModuleConfig(type = FxModuleType.DELAY,
            enabled = false, wet = 1f, depth = 1f))
        repeat(4_000) { i ->
            val l = sin(i * 0.017).toFloat()
            fx.process(l, -l, out)
            assertEquals(l, out[0], 0f)
            assertEquals(-l, out[1], 0f)
        }
        fx.process(Float.NaN, Float.POSITIVE_INFINITY, out)
        assertEquals(0f, out[0], 0f)
        assertEquals(0f, out[1], 0f)
    }

    @Test fun saturationProducesRealNonlinearSoundButMixZeroIsTransparent() {
        val fx = ModularFxChain(8_000)
        fx.setSlot(0, FxModuleConfig(FxModuleType.SATURATION,
            enabled = true, wet = 1f, depth = 1f))
        val out = FloatArray(2)
        repeat(400) { fx.process(0.4f, -0.4f, out) }
        assertTrue("Drive is audible", out[0] > 0.7f)
        assertEquals(-out[0], out[1], 0.0001f)
        fx.setSlot(0, fx.slot(0).copy(wet = 0f))
        repeat(90) { fx.process(0.4f, -0.4f, out) }
        assertEquals(0.4f, out[0], 0f)
        assertEquals(-0.4f, out[1], 0f)
    }

    @Test fun stereoTremoloHasIndependentPhaseAndRateAccurateMovement() {
        val fx = ModularFxChain(8_000)
        fx.setSlot(0, FxModuleConfig(
            FxModuleType.TREMOLO, enabled = true, wet = 1f,
            depth = 1f, rate = 0.6f, stereo = 1f,
        ))
        val out = FloatArray(2)
        var min = 1f
        var max = 0f
        var stereoDifference = 0.0
        repeat(8_000) { i ->
            fx.process(0.65f, 0.65f, out)
            if (i > 200) {
                min = minOf(min, out[0])
                max = maxOf(max, out[0])
                stereoDifference += abs((out[0] - out[1]).toDouble())
            }
            assertBounded(out)
        }
        assertTrue("Tremolo must modulate depth over time", max - min > 0.35f)
        assertTrue("Stereo phase should make L/R genuinely different", stereoDifference > 100.0)
    }

    @Test fun delayEchoIsScheduledInSecondsAtEightAndFortyEightKhz() {
        for (sampleRate in intArrayOf(8_000, 48_000)) {
            val fx = ModularFxChain(sampleRate)
            fx.setSlot(0, FxModuleConfig(
                FxModuleType.DELAY, enabled = true, wet = 1f,
                depth = 0f, rate = 0f, stereo = 0f,
            ))
            val out = FloatArray(2)
            val echoIndex = (0.025f * sampleRate).toInt()
            var echoAmplitude = 0f
            repeat(echoIndex + 20) { frame ->
                fx.process(if (frame == 0) 1f else 0f, 0f, out)
                if (frame == echoIndex) echoAmplitude = out[0]
                if (frame > echoIndex + 1) assertEquals(0f, out[1], 0f)
            }
            assertTrue("Echo at 25ms for sample rate $sampleRate", echoAmplitude > 0.7f)
        }
    }

    @Test fun liveTopologyChangeFadesOldEffectThroughDry() {
        val fx = ModularFxChain(8_000)
        fx.setSlot(0, FxModuleConfig(FxModuleType.SATURATION,
            enabled = true, wet = 1f, depth = 1f))
        val out = FloatArray(2)
        repeat(500) { fx.process(0.4f, 0.4f, out) }
        fx.setSlot(0, FxModuleConfig(FxModuleType.TREMOLO,
            enabled = true, wet = 1f, depth = 1f, rate = 0.7f))
        var maxAdjacentDelta = 0f
        var previous = out[0]
        repeat(200) {
            fx.process(0.4f, 0.4f, out)
            maxAdjacentDelta = maxOf(maxAdjacentDelta, abs(out[0] - previous))
            previous = out[0]
            assertBounded(out)
        }
        assertTrue("Type switch should not jump to a new topology", maxAdjacentDelta < 0.04f)
        assertEquals(FxModuleType.TREMOLO, fx.slot(0).type)
    }

    @Test fun flexibleOrderAndRepeatedProcessorsProduceDistinctTimbres() {
        val a = ModularFxChain(8_000)
        val b = ModularFxChain(8_000)
        val distortion = FxModuleConfig(FxModuleType.SATURATION,
            enabled = true, wet = 1f, depth = 1f)
        val movement = FxModuleConfig(FxModuleType.TREMOLO,
            enabled = true, wet = 1f, depth = 0.9f, rate = 0.83f)
        a.setSlot(0, distortion)
        a.setSlot(1, movement)
        b.setSlot(0, distortion)
        b.setSlot(1, movement)
        b.moveSlot(0, 1)
        assertEquals(FxModuleType.TREMOLO, b.slot(0).type)
        assertEquals(FxModuleType.SATURATION, b.slot(1).type)
        val left = FloatArray(2)
        val right = FloatArray(2)
        var sum = 0.0
        repeat(4_000) { frame ->
            val input = (0.5 * sin(frame * 0.063)).toFloat()
            a.process(input, input, left)
            b.process(input, input, right)
            sum += abs((left[0] - right[0]).toDouble())
        }
        assertTrue("Routing order must change the audible result", sum > 10.0)
    }

    @Test fun effectsAreBitIdenticalAcrossAnyOfflineBufferPartition() {
        val oneBlock = configuredComplexChain()
        val chunks = configuredComplexChain()
        val frames = 4_096
        val input = FloatArray(frames * 2) { n ->
            (0.44 * sin(n * 0.091) + 0.16 * sin(n * 0.013)).toFloat()
        }
        val whole = input.copyOf()
        oneBlock.processInterleaved(whole, frames)
        val chunked = FloatArray(whole.size)
        val buffer = FloatArray(256 * 2)
        for (section in 0 until frames / 256) {
            System.arraycopy(input, section * buffer.size, buffer, 0, buffer.size)
            chunks.processInterleaved(buffer, 256)
            System.arraycopy(buffer, 0, chunked, section * buffer.size, buffer.size)
        }
        assertArrayEquals("Every modulation and echo must be sample-addressed",
            whole, chunked, 0f)
    }

    @Test fun disablingDelayRetiresItsOldEchoHistory() {
        val fx = ModularFxChain(8_000)
        val delay = FxModuleConfig(FxModuleType.DELAY,
            enabled = true, wet = 1f, depth = 0.7f, rate = 0.1f)
        fx.setSlot(0, delay)
        val out = FloatArray(2)
        repeat(10_000) { frame ->
            fx.process(if (frame % 1000 == 0) 0.9f else 0f, 0f, out)
            assertBounded(out)
        }
        fx.setSlot(0, delay.copy(enabled = false))
        repeat(200) { fx.process(0f, 0f, out) }
        assertEquals(0f, out[0], 0f)
        fx.setSlot(0, delay)
        repeat(10_000) {
            fx.process(0f, 0f, out)
            assertEquals("Old echoes must not return after disabling", 0f, out[0], 0f)
            assertEquals(0f, out[1], 0f)
        }
    }

    @Test fun invalidEditsAndAggressiveRealTimeAutomationStayFiniteAndBounded() {
        val fx = ModularFxChain(8_000, slotCount = 8)
        val initial = fx.slot(0)
        fx.setSlot(0, FxModuleConfig(FxModuleType.DELAY, true, Float.NaN))
        assertEquals(initial, fx.slot(0))
        fx.setSlot(0, FxModuleConfig(FxModuleType.DELAY,
            enabled = true, wet = 30f, depth = -8f, rate = 3f, stereo = -2f))
        assertEquals(1f, fx.slot(0).wet, 0f)
        assertEquals(0f, fx.slot(0).depth, 0f)
        val kinds = FxModuleType.entries
        val out = FloatArray(2)
        repeat(30_000) { frame ->
            if (frame % 127 == 0) {
                for (i in 0 until fx.slotCount) {
                    fx.setSlot(i, FxModuleConfig(
                        kinds[(frame / 127 + i) % kinds.size],
                        enabled = i % 3 != frame % 3,
                        wet = (frame % 110) / 100f,
                        depth = (frame % 100) / 100f,
                        rate = (i * 13 % 100) / 100f,
                        stereo = (i * 9 % 100) / 100f,
                    ))
                }
            }
            val sample = sin(frame * 0.11).toFloat()
            fx.process(sample, -sample, out)
            assertBounded(out)
        }
        fx.clearAll()
        fx.process(0.75f, -0.75f, out)
        assertEquals(0.75f, out[0], 0f)
        assertEquals(-0.75f, out[1], 0f)
    }

    private fun configuredComplexChain() = ModularFxChain(8_000).apply {
        setSlot(0, FxModuleConfig(FxModuleType.SATURATION, true, .7f, .75f))
        setSlot(1, FxModuleConfig(FxModuleType.TREMOLO, true, .4f, .9f, .8f, .8f))
        setSlot(2, FxModuleConfig(FxModuleType.CHORUS, true, .6f, .5f, .6f, 1f))
        setSlot(3, FxModuleConfig(FxModuleType.DELAY, true, .55f, .5f, .03f, .65f))
    }

    private fun assertBounded(samples: FloatArray) {
        for (sample in samples) {
            assertTrue("Nonfinite FX sample", sample.isFinite())
            assertTrue("FX escaped [-1,1]", abs(sample) <= 1.00001f)
        }
    }
}
