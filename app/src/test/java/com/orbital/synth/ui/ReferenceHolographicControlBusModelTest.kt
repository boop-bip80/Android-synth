package com.orbital.synth.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceHolographicControlBusModelTest {
    @Test fun exactlyFiveMacrolightsFollowStableOrderedNormalizedDspSnapshots() {
        val controls = ReferenceControlBusModel.nodes(
            listOf(0f, .25f, .5f, .75f, 1f),
        )
        assertEquals(5, controls.size)
        assertEquals(listOf(.1f, .3f, .5f, .7f, .9f), controls.map { it.x })
        assertEquals(listOf(0f, .25f, .5f, .75f, 1f), controls.map { it.value })
        assertTrue(controls.zipWithNext().all { (a, b) -> a.x < b.x })
        assertTrue(controls.all { it.y in 0f..1f })
        assertTrue("Signal bus must stay in the knob-disc band, above text labels",
            controls.all { it.y in .31f.. .43f })
        assertTrue(controls.zipWithNext().all { (a, b) -> a.y > b.y })
    }

    @Test fun missingNonfiniteAndWildValuesNeverProduceInvalidCanvasPositions() {
        val values = listOf(Float.NaN, Float.POSITIVE_INFINITY, -9f, 9f)
        val a = ReferenceControlBusModel.nodes(values)
        val b = ReferenceControlBusModel.nodes(values)
        assertEquals(a, b)
        assertEquals(listOf(.5f, .5f, 0f, 1f, .5f), a.map { it.value })
        assertTrue(a.all { it.x.isFinite() && it.y.isFinite() && it.value.isFinite() })
        assertTrue(a.all { it.x in 0f..1f && it.y in 0f..1f })
    }

    @Test fun onlyAcceptedPcmIntensityChangesLightNotSyntheticWaveShape() {
        assertEquals(0f, ReferenceControlBusModel.pcmLight(Float.NaN), 0f)
        assertEquals(0f, ReferenceControlBusModel.pcmLight(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(0f, ReferenceControlBusModel.pcmLight(-2f), 0f)
        assertEquals(1f, ReferenceControlBusModel.pcmLight(1f), 0f)
        assertEquals(0f, ReferenceControlBusModel.pcmLight(Float.POSITIVE_INFINITY), 0f)
        assertTrue(ReferenceControlBusModel.pcmLight(.03f) <
            ReferenceControlBusModel.pcmLight(.18f))
        assertEquals(
            ReferenceControlBusModel.nodes(listOf(.1f,.2f,.3f,.4f,.5f)),
            ReferenceControlBusModel.nodes(listOf(.1f,.2f,.3f,.4f,.5f)),
        )
    }
}
