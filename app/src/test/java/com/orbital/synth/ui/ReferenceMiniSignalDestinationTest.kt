package com.orbital.synth.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceMiniSignalDestinationTest {
    @Test fun eachPreviewHasOneHonestRealEditorAndNoSyntheticAudioDestination() {
        assertEquals(4, ReferenceMiniSignalDestination.entries.size)
        assertEquals(
            listOf("ADSR envelope", "LFO source controls", "XY routing modes",
                "64-route modulation matrix"),
            ReferenceMiniSignalDestination.entries.map { it.editorLabel },
        )
        assertTrue(ReferenceMiniSignalDestination.entries.all {
            it.editorLabel.isNotBlank() && !it.editorLabel.contains("FFT", true)
        })
    }

    @Test fun modulationTileCannotAdvertiseEditingWithoutLiveHostCallbacks() {
        for (destination in ReferenceMiniSignalDestination.entries) {
            assertEquals(destination.name, destination != ReferenceMiniSignalDestination.MODULATION,
                destination.canOpen(modulationRackConnected = false))
            assertTrue(destination.name, destination.canOpen(modulationRackConnected = true))
        }
        assertFalse(ReferenceMiniSignalDestination.MODULATION.canOpen(false))
    }
}
