package com.orbital.synth.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiDeviceOpenStateTest {
    @Test
    fun oldCallbackCannotAttachAfterSameIdIsDetachedAndReadded() {
        val state = MidiDeviceOpenState()
        val oldEpoch = state.beginOpen(42, connected = false)!!
        assertTrue(state.isCurrentOpening(42, oldEpoch, connected = false))

        state.onDetached(42)
        assertFalse(state.isCurrentOpening(42, oldEpoch, connected = false))
        assertNull(state.beginOpen(42, connected = false))

        state.onAttached(42)
        val newEpoch = state.beginOpen(42, connected = false)!!
        assertTrue(oldEpoch != newEpoch)
        assertFalse(state.isCurrentOpening(42, oldEpoch, connected = false))
        assertTrue(state.isCurrentOpening(42, newEpoch, connected = false))
    }

    @Test
    fun staleCompletionCannotCancelNewerOpening() {
        val state = MidiDeviceOpenState()
        val oldEpoch = state.beginOpen(9, connected = false)!!
        state.onDetached(9)
        state.onAttached(9)
        val newEpoch = state.beginOpen(9, connected = false)!!

        state.finishOpen(9, oldEpoch)
        assertTrue(state.isCurrentOpening(9, newEpoch, connected = false))
        assertNull(state.beginOpen(9, connected = false))

        state.finishOpen(9, newEpoch)
        assertFalse(state.isCurrentOpening(9, newEpoch, connected = false))
        assertTrue(state.beginOpen(9, connected = false) != null)
    }

    @Test
    fun duplicateOpenOrAlreadyConnectedPortIsRejected() {
        val state = MidiDeviceOpenState()
        assertNull(state.beginOpen(12, connected = true))
        val epoch = state.beginOpen(12, connected = false)!!
        assertNull(state.beginOpen(12, connected = false))
        assertFalse(state.isCurrentOpening(12, epoch, connected = true))

        state.finishOpen(12, epoch)
        assertNull(state.beginOpen(12, connected = true))
        assertTrue(state.beginOpen(12, connected = false) != null)
    }

    @Test
    fun detachedPortsCannotReopenUntilNewAddAndStoppedStateIsCleared() {
        val state = MidiDeviceOpenState()
        val first = state.beginOpen(23, connected = false)!!
        state.onDetached(23)
        assertNull(state.beginOpen(23, connected = false))
        assertFalse(state.isCurrentOpening(23, first, connected = false))

        // A manager stop invalidates callbacks using its own session generation.
        // A new session may enumerate a device with this same framework ID.
        state.clear()
        assertTrue(state.beginOpen(23, connected = false) != null)
    }
}
