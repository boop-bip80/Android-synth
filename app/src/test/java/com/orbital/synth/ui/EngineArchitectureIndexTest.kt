package com.orbital.synth.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineArchitectureIndexTest {
    @Test fun fiveEngineDetentsClampAndRound() {
        assertEquals(0, engineArchitectureIndex(-1f))
        assertEquals(0, engineArchitectureIndex(0f))
        assertEquals(1, engineArchitectureIndex(.25f))
        assertEquals(2, engineArchitectureIndex(.5f))
        assertEquals(3, engineArchitectureIndex(.75f))
        assertEquals(4, engineArchitectureIndex(1f))
        assertEquals(4, engineArchitectureIndex(2f))
    }

    @Test fun midpointsKeepEngineSelectionsStable() {
        assertEquals(0, engineArchitectureIndex(.10f))
        assertEquals(1, engineArchitectureIndex(.36f))
        assertEquals(2, engineArchitectureIndex(.62f))
        assertEquals(3, engineArchitectureIndex(.86f))
        assertEquals(4, engineArchitectureIndex(.90f))
    }
}
