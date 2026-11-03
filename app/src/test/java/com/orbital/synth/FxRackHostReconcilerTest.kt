package com.orbital.synth

import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FxRackHostReconcilerTest {
    private val drive = FxModuleConfig(FxModuleType.SATURATION, true, .44f)
    private val delay = FxModuleConfig(FxModuleType.DELAY, true, .62f)
    private val chorus = FxModuleConfig(FxModuleType.CHORUS, true, .38f)

    @Test fun newSlotEditsPublishImmediatelyWhenNoMoveIsPending() {
        val coordinator = FxRackHostReconciler()
        val writes = mutableListOf<Pair<Int, FxModuleConfig>>()
        val visible = coordinator.stageEdit(List(6) { FxModuleConfig() }, 2, chorus) {
            index, config -> writes += index to config
        }
        assertEquals(chorus, visible[2])
        assertEquals(listOf(2 to chorus), writes)
        assertFalse(coordinator.hasPendingMove)
    }

    @Test fun moveRetainsOptimisticUiUntilRealWorkerTicketAcknowledgment() {
        val coordinator = FxRackHostReconciler()
        val original = listOf(drive, delay, FxModuleConfig())
        val visible = coordinator.stageMove(original, 0, 1, ticket = 7L)
        assertEquals(listOf(delay, drive, FxModuleConfig()), visible)
        assertTrue(coordinator.hasPendingMove)
        val writes = mutableListOf<Pair<Int, FxModuleConfig>>()
        assertEquals(
            visible,
            coordinator.reconcile(visible, original, lastAppliedTicket = 6L,
                lastCanceledTicket = 0L) { i, c -> writes += i to c },
        )
        assertTrue(writes.isEmpty())
        assertEquals(
            visible,
            coordinator.reconcile(visible, visible, lastAppliedTicket = 7L,
                lastCanceledTicket = 0L) { i, c -> writes += i to c },
        )
        assertTrue(writes.isEmpty())
        assertFalse(coordinator.hasPendingMove)
    }

    @Test fun fastPostMoveWetEditWaitsAndTargetsMovedProcessorIndex() {
        val coordinator = FxRackHostReconciler()
        val original = listOf(drive, delay, FxModuleConfig())
        var visible = coordinator.stageMove(original, 0, 1, ticket = 12L)
        val newDrive = drive.copy(wet = .91f, depth = .87f)
        val writes = mutableListOf<Pair<Int, FxModuleConfig>>()
        visible = coordinator.stageEdit(visible, 1, newDrive) { i, c -> writes += i to c }
        assertTrue(writes.isEmpty())
        // The worker has not yet moved stages. Publishing to slot 1 now would
        // accidentally alter DELAY instead of the moved SATURATION processor.
        coordinator.reconcile(visible, original, 11L, 0L) { i, c -> writes += i to c }
        assertTrue(writes.isEmpty())
        val reorderedWorker = listOf(delay, drive, FxModuleConfig())
        val settled = coordinator.reconcile(visible, reorderedWorker, 12L, 0L) {
            i, c -> writes += i to c
        }
        assertEquals(listOf(1 to newDrive), writes)
        assertEquals(newDrive, settled[1])
        assertFalse(coordinator.hasPendingMove)
    }

    @Test fun identicalAdjacentProcessorsStillRequireTicketNotListEquality() {
        val coordinator = FxRackHostReconciler()
        val identical = listOf(drive, drive)
        val visible = coordinator.stageMove(identical, 0, 1, 5L)
        assertEquals(identical, visible)
        coordinator.reconcile(visible, identical, 4L, 0L) { _, _ ->
            error("Premature write")
        }
        assertTrue(coordinator.hasPendingMove)
        coordinator.reconcile(visible, identical, 5L, 0L) { _, _ ->
            error("No edits were staged")
        }
        assertFalse(coordinator.hasPendingMove)
    }

    @Test fun repeatedMovesRemapStagedKnobEditsToLatestSlotIndex() {
        val coordinator = FxRackHostReconciler()
        val original = listOf(drive, delay, chorus)
        var visible = coordinator.stageMove(original, 0, 2, 20L)
        val editedDrive = drive.copy(wet = .83f)
        visible = coordinator.stageEdit(visible, 2, editedDrive) { _, _ ->
            error("Must not publish while moving")
        }
        visible = coordinator.stageMove(visible, 2, 0, 21L)
        assertEquals(editedDrive, visible[0])
        val writes = mutableListOf<Pair<Int, FxModuleConfig>>()
        // Worker has finished the first, but not the SECOND queued move.
        coordinator.reconcile(visible, listOf(delay, chorus, drive), 20L, 0L) {
            i, c -> writes += i to c
        }
        assertTrue(writes.isEmpty())
        val result = coordinator.reconcile(visible, original, 21L, 0L) {
            i, c -> writes += i to c
        }
        assertEquals(listOf(0 to editedDrive), writes)
        assertEquals(editedDrive, result[0])
    }

    @Test fun patchEpochCancellationDiscardsStaleMovedEditsEvenIfTicketWasApplied() {
        val coordinator = FxRackHostReconciler()
        val original = listOf(drive, delay)
        var visible = coordinator.stageMove(original, 0, 1, 31L)
        visible = coordinator.stageEdit(visible, 1, drive.copy(wet = .9f)) { _, _ ->
            error("Should be delayed")
        }
        val recalled = listOf(chorus, FxModuleConfig())
        val result = coordinator.reconcile(visible, recalled, 31L, 31L) { _, _ ->
            error("Never contaminate a newly recalled patch")
        }
        assertEquals(recalled, result)
        assertFalse(coordinator.hasPendingMove)
    }

    @Test fun invalidSlotAndNonfiniteKnobValuesNeverPublishToDsp() {
        val coordinator = FxRackHostReconciler()
        val original = listOf(drive)
        val writes = mutableListOf<Pair<Int, FxModuleConfig>>()
        assertEquals(original, coordinator.stageEdit(original, 3, drive) { i, c ->
            writes += i to c
        })
        assertEquals(original, coordinator.stageEdit(
            original, 0, drive.copy(wet = Float.NaN),
        ) { i, c -> writes += i to c })
        assertTrue(writes.isEmpty())
        assertEquals(original, coordinator.stageMove(original, 0, 0, ticket = 0L))
        assertFalse(coordinator.hasPendingMove)
    }
}
