package com.orbital.synth.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusSessionStateTest {
    @Test
    fun transientLossRetainsClaimAndGainResumesExactlyOnce() {
        val state = AudioFocusSessionState()
        var requests = 0
        var abandons = 0
        state.requestPlayback()
        assertTrue(state.acquireClaim { requests++; true })
        assertTrue(state.transientLoss())
        assertTrue(state.awaitingGain)
        assertTrue(state.retainClaimAfterWorkerExit(restartQueued = false))
        assertTrue(state.hasClaim) // Callback registration must survive worker exit.

        assertTrue(state.gain())
        assertFalse(state.gain()) // Duplicate gain cannot create a second worker.
        assertFalse(state.awaitingGain)
        assertEquals(1, requests)
        assertEquals(0, abandons)

        state.cancelPlayback()
        state.abandonClaim { abandons++ }
        assertEquals(1, abandons)
        assertFalse(state.hasClaim)
    }

    @Test
    fun explicitActivityStopCancelsAQueuedTransientResume() {
        val state = AudioFocusSessionState()
        state.requestPlayback()
        assertTrue(state.acquireClaim { true })
        assertTrue(state.transientLoss())

        state.cancelPlayback() // onStop or close wins over a late gain.
        var abandons = 0
        state.abandonClaim { abandons++ }
        assertFalse(state.retainClaimAfterWorkerExit(restartQueued = true))
        assertFalse(state.gain())
        assertEquals(1, abandons)
        assertFalse(state.playbackRequested)
    }

    @Test
    fun rapidStopStartKeepsNewClaimAndDoesNotAbandonItDuringOldWorkerExit() {
        val state = AudioFocusSessionState()
        val calls = mutableListOf<String>()

        state.requestPlayback()
        assertTrue(state.acquireClaim { calls += "request-old"; true })
        state.cancelPlayback()
        state.abandonClaim { calls += "abandon-old" }

        // New onStart wins after the old stop's focus release. While the old
        // render worker unwinds, the new start queues its replacement.
        state.requestPlayback()
        assertTrue(state.acquireClaim { calls += "request-new"; true })
        assertTrue(state.retainClaimAfterWorkerExit(restartQueued = true))
        if (!state.retainClaimAfterWorkerExit(restartQueued = true)) {
            state.abandonClaim { calls += "abandon-new" }
        }

        assertEquals(
            listOf("request-old", "abandon-old", "request-new"), calls
        )
        assertTrue(state.hasClaim)
        state.abandonClaim { calls += "abandon-new" }
        assertEquals("abandon-new", calls.last())
    }

    @Test
    fun permanentLossAbandonsFocusAndNeverAutomaticallyResumes() {
        val state = AudioFocusSessionState()
        state.requestPlayback()
        assertTrue(state.acquireClaim { true })
        state.permanentLoss()
        assertFalse(state.retainClaimAfterWorkerExit(restartQueued = false))
        assertFalse(state.gain())
        var abandoned = 0
        state.abandonClaim { abandoned++ }
        assertEquals(1, abandoned)
        assertFalse(state.playbackRequested)
        assertFalse(state.hasClaim)
    }

    @Test
    fun unavailableAudioFocusDoesNotCreateAFakeClaim() {
        val state = AudioFocusSessionState()
        state.requestPlayback()
        assertFalse(state.acquireClaim { false })
        assertFalse(state.transientLoss())
        assertFalse(state.gain())
        assertFalse(state.hasClaim)
    }
}
