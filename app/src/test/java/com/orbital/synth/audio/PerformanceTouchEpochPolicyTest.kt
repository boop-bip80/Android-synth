package com.orbital.synth.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceTouchEpochPolicyTest {
    @Test fun exactlyOneSynchronousForegroundStartDoesNotCancelFirstNewNote() {
        assertEquals(
            16L,
            PerformanceTouchEpochPolicy.acknowledgeExpectedStart(
                previouslyObserved = 15L,
                beforeStart = 15L,
                afterStart = 16L,
                accepted = true,
                running = true,
            ),
        )
    }

    @Test fun pendingWorkerOrFocusAwaitCannotPretendToHaveStarted() {
        assertEquals(
            31L,
            PerformanceTouchEpochPolicy.acknowledgeExpectedStart(
                previouslyObserved = 31L,
                beforeStart = 31L,
                afterStart = 31L,
                accepted = true, // pending restart / focus awaiting gain
                running = false,
            ),
        )
        assertEquals(
            31L,
            PerformanceTouchEpochPolicy.acknowledgeExpectedStart(
                previouslyObserved = 31L,
                beforeStart = 31L,
                afterStart = 32L,
                accepted = false,
                running = true,
            ),
        )
    }

    @Test fun unexpectedFailureReplacementOrOldUnobservedEventsStayUnacknowledged() {
        assertEquals(
            9L,
            PerformanceTouchEpochPolicy.acknowledgeExpectedStart(
                previouslyObserved = 9L,
                beforeStart = 9L,
                afterStart = 12L, // worker started, failed, restarted
                accepted = true,
                running = true,
            ),
        )
        assertEquals(
            9L,
            PerformanceTouchEpochPolicy.acknowledgeExpectedStart(
                previouslyObserved = 9L,
                beforeStart = 11L, // unobserved external focus loss
                afterStart = 12L,
                accepted = true,
                running = true,
            ),
        )
        assertEquals(
            null,
            PerformanceTouchEpochPolicy.acknowledgeExpectedStart(
                previouslyObserved = null,
                beforeStart = 0L,
                afterStart = 1L,
                accepted = true,
                running = true,
            ),
        )
    }
}
