package com.orbital.synth.core.music

import org.junit.Assert.assertEquals
import org.junit.Test

class SequencerToggleReconcilerTest {
    @Test fun pendingMuteSurvivesWorkerDelayAndCanBeSavedWithoutDataLoss() {
        val intents = SequencerToggleReconciler()
        intents.record(selectionRevision = 3L, step = 0, enabled = false, ticket = 11L)
        val displayed = intents.reconcile(
            selectionRevision = 3L, appliedToggleId = 10L,
            workerEnabled = setOf(0, 2), stepCount = 16,
        )
        assertEquals(setOf(2), displayed)
        // The same state becomes the V2 Save Sequence enabled-step snapshot.
        assertEquals(setOf(2), displayed.toSet())
        assertEquals(
            setOf(2),
            intents.reconcile(3L, 10L, setOf(0, 2), 16),
        )
    }

    @Test fun quickSecondTapWinsEvenAfterFirstCommandIsAcknowledged() {
        val intents = SequencerToggleReconciler()
        intents.record(3L, step = 0, enabled = false, ticket = 11L)
        assertEquals(emptySet<Int>(), intents.reconcile(3L, 10L, setOf(0), 16))
        intents.record(3L, step = 0, enabled = true, ticket = 12L)
        // Worker still shows the original ON state before processing either tap.
        assertEquals(setOf(0), intents.reconcile(3L, 10L, setOf(0), 16))
        // Worker has now applied OFF but the second ON command is still queued.
        assertEquals(setOf(0), intents.reconcile(3L, 11L, emptySet(), 16))
        // After the second command is applied its snapshot becomes authoritative.
        assertEquals(setOf(0), intents.reconcile(3L, 12L, setOf(0), 16))
        assertEquals(emptySet<Int>(), intents.reconcile(3L, 12L, emptySet(), 16))
    }

    @Test fun selectionRevisionChangesDiscardOldPendingToggle() {
        val intents = SequencerToggleReconciler()
        intents.record(2L, step = 0, enabled = false, ticket = 1L)
        assertEquals(setOf(4), intents.reconcile(3L, 0L, setOf(0, 4), 5))
    }

    @Test fun laterNoteEditCanReplacePendingToggleAndResizeDropsTruncatedStep() {
        val intents = SequencerToggleReconciler()
        intents.record(5L, step = 3, enabled = false, ticket = 19L)
        intents.clearStep(3) // editing a note after the toggle enables the step
        assertEquals(setOf(3), intents.reconcile(5L, 18L, setOf(3), 16))

        intents.record(5L, step = 15, enabled = true, ticket = 20L)
        assertEquals(setOf(3), intents.reconcile(5L, 19L, setOf(3), 8))
    }
}
