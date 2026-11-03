package com.orbital.synth.ui

import com.orbital.synth.core.ParameterId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceXyRoutingTest {
    @Test fun allSixModesMapOnlyToTheirActualEngineParameters() {
        val pairs = mapOf(
            ReferenceXyMode.PERFORMANCE to (ParameterId.MORPH to ParameterId.MOVEMENT),
            ReferenceXyMode.FILTER to (ParameterId.CUTOFF to ParameterId.RESONANCE),
            ReferenceXyMode.CORE_VIBRATO to (ParameterId.LFO_RATE to ParameterId.LFO_DEPTH),
            ReferenceXyMode.WAVE_SHAPE to (ParameterId.WAVE to ParameterId.MORPH),
            ReferenceXyMode.FX_SPACE to (ParameterId.FX_AMOUNT to ParameterId.MOVEMENT),
            ReferenceXyMode.FM_COLOR to (ParameterId.MORPH to ParameterId.CHARACTER),
        )
        assertEquals(6, ReferenceXyMode.entries.size)
        assertEquals(ReferenceXyMode.entries.toSet(), pairs.keys)
        pairs.forEach { (mode, pair) ->
            assertEquals(mode.name, pair.first, mode.xParameterId)
            assertEquals(mode.name, pair.second, mode.yParameterId)
            assertNotEquals(mode.name, mode.xParameterId, mode.yParameterId)
            assertTrue(mode.xLabel.isNotBlank())
            assertTrue(mode.yLabel.isNotBlank())
        }
    }

    @Test fun normalizedEngineQuantizerExactlyMatchesFiveDspArchitectures() {
        assertEquals(0, ReferenceXyRouting.engineIndex(0f))
        assertEquals(0, ReferenceXyRouting.engineIndex(0.12499f))
        assertEquals(1, ReferenceXyRouting.engineIndex(0.125f))
        assertEquals(1, ReferenceXyRouting.engineIndex(.37499f))
        assertEquals(2, ReferenceXyRouting.engineIndex(.375f))
        assertEquals(3, ReferenceXyRouting.engineIndex(.75f))
        assertEquals(4, ReferenceXyRouting.engineIndex(1f))
        assertEquals(0, ReferenceXyRouting.engineIndex(-2f))
        assertEquals(4, ReferenceXyRouting.engineIndex(200f))
        assertEquals(2, ReferenceXyRouting.engineIndex(Float.NaN))
    }

    @Test fun continuousWaveShapeIsNeverExposedForDiscreteFmOrPdWaveChoices() {
        listOf(0f, .25f, .5f).forEach { engine ->
            assertTrue(ReferenceXyMode.WAVE_SHAPE.enabledForEngine(engine))
            assertEquals(ReferenceXyMode.WAVE_SHAPE,
                ReferenceXyMode.WAVE_SHAPE.resolveFallback(engine))
        }
        listOf(.75f, 1f).forEach { engine ->
            assertFalse(ReferenceXyMode.WAVE_SHAPE.enabledForEngine(engine))
            assertEquals(ReferenceXyMode.PERFORMANCE,
                ReferenceXyMode.WAVE_SHAPE.resolveFallback(engine))
        }
    }

    @Test fun fmColorWorksOnlyWhenTheSynthActuallyUsesSixOperatorFm() {
        for (engine in 0..4) {
            val normalized = engine / 4f
            val enabled = ReferenceXyMode.FM_COLOR.enabledForEngine(normalized)
            assertEquals("engine $engine", engine == 3, enabled)
            assertEquals(if (engine == 3) ReferenceXyMode.FM_COLOR
                else ReferenceXyMode.PERFORMANCE,
                ReferenceXyMode.FM_COLOR.resolveFallback(normalized))
        }
    }

    @Test fun universalModesWorkOnAllEnginesAndPaletteHidesInvalidModes() {
        val universal = listOf(
            ReferenceXyMode.PERFORMANCE,
            ReferenceXyMode.FILTER,
            ReferenceXyMode.CORE_VIBRATO,
            ReferenceXyMode.FX_SPACE,
        )
        for (engine in 0..4) {
            val normalized = engine / 4f
            universal.forEach { mode ->
                assertTrue(mode.enabledForEngine(normalized))
                assertEquals(mode, mode.resolveFallback(normalized))
            }
            val available = ReferenceXyRouting.availableModes(normalized)
            assertTrue(available.containsAll(universal))
            assertEquals(engine <= 2, ReferenceXyMode.WAVE_SHAPE in available)
            assertEquals(engine == 3, ReferenceXyMode.FM_COLOR in available)
            assertEquals(if (engine == 3) 5 else if (engine == 4) 4 else 5,
                available.size)
        }
    }

    @Test fun badPointerNumbersNeverReachHostAsNanOrOutOfBounds() {
        assertEquals(.5f, ReferenceXyRouting.normalizeValue(Float.NaN), 0f)
        assertEquals(.5f, ReferenceXyRouting.normalizeValue(Float.POSITIVE_INFINITY), 0f)
        assertEquals(.5f, ReferenceXyRouting.normalizeValue(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(0f, ReferenceXyRouting.normalizeValue(-5f), 0f)
        assertEquals(1f, ReferenceXyRouting.normalizeValue(5f), 0f)
        assertEquals(.65f, ReferenceXyRouting.normalizeValue(.65f), 0f)
        val point = ReferenceXyRouting.normalizePoint(Float.NaN, 5f)
        assertEquals(.5f, point.x, 0f)
        assertEquals(1f, point.y, 0f)
    }

    @Test fun allModesHavePreciseTalkBackAxisActionsAndTruthfulCaveats() {
        ReferenceXyMode.entries.forEach { mode ->
            assertTrue(mode.talkBackLabel.contains(mode.xLabel))
            assertTrue(mode.talkBackLabel.contains(mode.yLabel))
            assertTrue(mode.talkBackLabel.contains(mode.guidance))
            assertEquals("Increase ${mode.xLabel}",
                mode.actionLabel(ReferenceXyAxis.X, ReferenceXyDirection.INCREASE))
            assertEquals("Decrease ${mode.xLabel}",
                mode.actionLabel(ReferenceXyAxis.X, ReferenceXyDirection.DECREASE))
            assertEquals("Increase ${mode.yLabel}",
                mode.actionLabel(ReferenceXyAxis.Y, ReferenceXyDirection.INCREASE))
            assertEquals("Decrease ${mode.yLabel}",
                mode.actionLabel(ReferenceXyAxis.Y, ReferenceXyDirection.DECREASE))
        }
        assertTrue(ReferenceXyMode.CORE_VIBRATO.guidance.contains("not the eight-LFO rack"))
        assertTrue(ReferenceXyMode.CORE_VIBRATO.guidance.contains("Movement above zero"))
        assertTrue(ReferenceXyMode.FX_SPACE.guidance.contains("FX must be above zero"))
        assertTrue(ReferenceXyMode.FM_COLOR.guidance.contains("engine family three"))
        assertEquals(ReferenceXyMode.PERFORMANCE, ReferenceXyRouting.defaultMode)
    }
}
