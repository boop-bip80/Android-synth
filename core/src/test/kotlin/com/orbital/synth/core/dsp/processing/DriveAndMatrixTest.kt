package com.orbital.synth.core.dsp.processing

import org.junit.Assert.*
import org.junit.Test

class DriveAndMatrixTest {
    @Test fun driveHasExactDryPathAndStableSaturation() {
        for (x in floatArrayOf(-2f, -0.5f, 0f, 0.25f, 1f)) {
            assertEquals(x, Drive.process(x, 0f), 0f)
            assertEquals(-Drive.process(x, 1f), Drive.process(-x, 1f), 1e-5f)
        }
        assertTrue(Drive.process(Float.NaN, 1f).isFinite())
        assertTrue(Drive.process(Float.POSITIVE_INFINITY, Float.NaN).isFinite())
    }

    @Test fun matrixAccumulatesAndClampsEightRoutes() {
        val matrix = ModulationMatrix()
        matrix.setRoute(0, ModSource.LFO1, ModDestination.CUTOFF, 0.4f)
        matrix.setRoute(1, ModSource.ENV1, ModDestination.CUTOFF, -0.1f)
        val base = FloatArray(ModDestination.entries.size) { 0.5f }
        val sources = FloatArray(ModSource.entries.size)
        sources[ModSource.LFO1.ordinal] = 0.5f
        sources[ModSource.ENV1.ordinal] = 1f
        val result = FloatArray(ModDestination.entries.size)
        matrix.apply(base, sources, result)
        assertEquals(0.6f, result[ModDestination.CUTOFF.ordinal], 1e-5f)
        assertEquals(0.5f, result[ModDestination.PAN.ordinal], 0f)
        matrix.clearRoute(1)
        matrix.apply(base, sources, result)
        assertEquals(0.7f, result[ModDestination.CUTOFF.ordinal], 1e-5f)
        matrix.setRoute(2, ModSource.MOD_WHEEL, ModDestination.MORPH, 1f)
        sources[ModSource.MOD_WHEEL.ordinal] = 1f
        matrix.apply(base, sources, result)
        assertEquals(1f, result[ModDestination.MORPH.ordinal], 0f)
    }

    @Test fun modulationRoutesCancelIndependentlyOfSlotOrder() {
        val base = FloatArray(ModDestination.entries.size) { 0.5f }
        val sources = FloatArray(ModSource.entries.size) { 1f }
        val output = FloatArray(ModDestination.entries.size)
        val forward = ModulationMatrix(2)
        forward.setRoute(0, ModSource.LFO1, ModDestination.PITCH, 0.8f)
        forward.setRoute(1, ModSource.ENV1, ModDestination.PITCH, -0.8f)
        forward.apply(base, sources, output)
        assertEquals(0.5f, output[ModDestination.PITCH.ordinal], 1e-5f)

        val reversed = ModulationMatrix(2)
        reversed.setRoute(0, ModSource.ENV1, ModDestination.PITCH, -0.8f)
        reversed.setRoute(1, ModSource.LFO1, ModDestination.PITCH, 0.8f)
        reversed.apply(base, sources, output)
        assertEquals(0.5f, output[ModDestination.PITCH.ordinal], 1e-5f)

        reversed.setRoute(1, ModSource.LFO1, ModDestination.PITCH, 1f)
        reversed.apply(base, sources, output)
        assertEquals(0.7f, output[ModDestination.PITCH.ordinal], 1e-5f)
    }

    @Test fun modulationMaySafelyUseDestinationAsBaseBuffer() {
        val matrix = ModulationMatrix(1)
        matrix.setRoute(0, ModSource.AFTERTOUCH, ModDestination.FX_AMOUNT, -0.25f)
        val buffer = FloatArray(ModDestination.entries.size) { 0.7f }
        val sources = FloatArray(ModSource.entries.size) { 1f }
        matrix.apply(buffer, sources, buffer)
        assertEquals(0.45f, buffer[ModDestination.FX_AMOUNT.ordinal], 1e-5f)
    }
}
