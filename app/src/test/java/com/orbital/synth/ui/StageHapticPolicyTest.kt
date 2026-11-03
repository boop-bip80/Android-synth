package com.orbital.synth.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageHapticPolicyTest {
    private val policy = StageHapticPolicy

    private fun eligible(
        event: StageHapticPolicy.Event = StageHapticPolicy.Event.NOTE_DOWN,
        enabled: Boolean = true,
        system: Boolean = true,
        view: Boolean = true,
        exploration: Boolean = false,
        now: Long = 1000L,
        last: Long = -1000L,
    ): Boolean = policy.shouldTrigger(
        optedIn = enabled,
        systemEnabled = system,
        viewEnabled = view,
        touchExplorationEnabled = exploration,
        event = event,
        atMs = now,
        previousAtMs = last,
    )

    @Test fun noTactileEventWithoutExplicitOptIn() {
        assertFalse(eligible(enabled = false))
        assertTrue(eligible(enabled = true))
    }

    @Test fun systemAndAccessibilityPreferencesOverrideOptIn() {
        assertFalse(eligible(system = false))
        assertFalse(eligible(view = false))
        assertFalse(eligible(exploration = true))
        assertTrue(eligible())
    }

    @Test fun movingDraggingAndLiftingNeverCauseHapticSpam() {
        assertFalse(eligible(event = StageHapticPolicy.Event.NOTE_MOVE))
        assertFalse(eligible(event = StageHapticPolicy.Event.NOTE_UP))
        assertFalse(eligible(event = StageHapticPolicy.Event.CONTROL_DRAG))
    }

    @Test fun rapidPolyphonicContactsRespectSharedNoteOnCooldown() {
        assertTrue(eligible(now = 1000L, last = 800L))
        assertFalse(eligible(now = 1001L, last = 1000L))
        assertFalse(eligible(now = 1139L, last = 1000L))
        assertTrue(eligible(now = 1140L, last = 1000L))
        assertTrue(eligible(now = 1300L, last = 1140L))
    }

    @Test fun timeRollbackAndInvalidTimestampsFailClosed() {
        assertFalse(eligible(now = 900L, last = 1000L))
        assertFalse(eligible(now = 1000L, last = 1000L))
        assertFalse(eligible(now = -1L, last = 0L))
        assertTrue(eligible(now = 0L, last = -1L))
    }
}