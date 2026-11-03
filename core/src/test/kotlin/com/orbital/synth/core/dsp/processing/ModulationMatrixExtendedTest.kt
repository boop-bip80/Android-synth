package com.orbital.synth.core.dsp.processing

import org.junit.Assert.*
import org.junit.Test

class ModulationMatrixExtendedTest {
    @Test fun bipolarCurvesHavePredictableMusicalShapes() {
        assertEquals(-0.25f, ModulationCurves.exponential(-0.5f), 0f)
        assertEquals(0.5f, ModulationCurves.soft(0.25f), 0f)
        assertEquals(-0.375f, ModulationCurves.eightSteps(-0.4f), 0f)
        assertEquals(0f, ModulationCurves.soft(Float.NaN), 0f)
    }

    @Test fun routeCurvesCanBeChangedWithoutRebuildingTheMatrix() {
        val matrix = ModulationMatrix(3)
        val base = FloatArray(ModDestination.entries.size) { 0.5f }
        val sources = FloatArray(ModSource.entries.size)
        val out = FloatArray(ModDestination.entries.size)
        sources[ModSource.TOUCH_Y.ordinal] = 0.5f
        matrix.setRoute(0, ModSource.TOUCH_Y, ModDestination.PAN, 0.5f, ModCurve.EXPONENTIAL)
        matrix.apply(base, sources, out)
        assertEquals(0.625f, out[ModDestination.PAN.ordinal], 1e-6f)
        matrix.setRouteCurve(0, ModCurve.LINEAR)
        matrix.apply(base, sources, out)
        assertEquals(0.75f, out[ModDestination.PAN.ordinal], 1e-6f)
        matrix.setRouteAmount(0, -0.5f)
        matrix.apply(base, sources, out)
        assertEquals(0.25f, out[ModDestination.PAN.ordinal], 1e-6f)
        matrix.setRouteEnabled(0, false)
        matrix.apply(base, sources, out)
        assertEquals(0.5f, out[ModDestination.PAN.ordinal], 0f)
        assertFalse(matrix.isRouteEnabled(0))
    }

    @Test fun oppositeHighDepthRoutesCancelBeforeClamping() {
        val matrix = ModulationMatrix(2)
        matrix.setRoute(0, ModSource.MACRO1, ModDestination.FOLD, 4f)
        matrix.setRoute(1, ModSource.MACRO2, ModDestination.FOLD, -4f)
        val base = FloatArray(ModDestination.entries.size) { 0.5f }
        val sources = FloatArray(ModSource.entries.size) { 1f }
        val out = FloatArray(ModDestination.entries.size)
        matrix.apply(base, sources, out)
        assertEquals(0.5f, out[ModDestination.FOLD.ordinal], 0f)
        matrix.clearRoute(1)
        matrix.apply(base, sources, out)
        assertEquals(1f, out[ModDestination.FOLD.ordinal], 0f)
        matrix.setRouteAmount(0, Float.NaN)
        matrix.apply(base, sources, out)
        assertEquals(0.5f, out[ModDestination.FOLD.ordinal], 0f)
    }
}
