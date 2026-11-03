package com.orbital.synth

import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import com.orbital.synth.core.preset.PatchCodec
import com.orbital.synth.core.preset.PatchDirectoryStore
import com.orbital.synth.preset.PatchOutcome
import com.orbital.synth.preset.UserPatchRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Actual synthesizer + audio-block FX move ticket + V4 user repository.
 * No fake FX engine or assumption that the reorder immediately takes effect.
 */
class LiveFxImmediateSaveTest {
    @get:Rule val temp = TemporaryFolder()

    private val oldDelay = FxModuleConfig(
        type = FxModuleType.DELAY,
        enabled = true,
        wet = .41f,
        depth = .55f,
        rate = .28f,
        stereo = .71f,
    )
    private val otherChorus = FxModuleConfig(
        type = FxModuleType.CHORUS,
        enabled = true,
        wet = .26f,
        depth = .4f,
        rate = .64f,
        stereo = .93f,
    )

    @Test fun immediatePatchSaveWaitsUntilWorkerReordersAndFlushesPostMoveWetEdit() = runBlocking {
        val synth = OrbitalSynth(sampleRate = 48_000, maxVoices = 4)
        synth.setFxSlot(0, oldDelay)
        synth.setFxSlot(1, otherChorus)
        val coordinator = FxRackHostReconciler()
        var visible = List(synth.fxSlotCount) { synth.fxSlot(it) }
        val ticket = synth.requestFxSlotMove(0, 1)
        assertTrue(ticket > 0L)
        visible = coordinator.stageMove(visible, 0, 1, ticket)
        val editedDelay = oldDelay.copy(wet = .96f, rate = .8f)
        visible = coordinator.stageEdit(visible, 1, editedDelay) { index, config ->
            synth.setFxSlot(index, config)
        }
        // The requested index 1 still refers to CHORUS until the worker reorders.
        assertEquals(otherChorus, synth.fxSlot(1))
        assertEquals(editedDelay, visible[1])

        val repository = UserPatchRepository(
            PatchDirectoryStore(temp.newFolder("fx-ordered-saved").toPath()),
            { mapOf("WAVE" to .7f) },
            {},
            Dispatchers.Unconfined,
            snapshotFxSlots = {
                List(synth.fxSlotCount) { synth.fxSlot(it) }
            },
        )
        val save = async(start = CoroutineStart.UNDISPATCHED) {
            val settled = awaitFxRackSettled(
                timeoutMillis = 1_000L,
                isPending = { coordinator.hasPendingMove },
                reconcile = {
                    visible = coordinator.reconcile(
                        visible = visible,
                        actual = List(synth.fxSlotCount) { synth.fxSlot(it) },
                        lastAppliedTicket = synth.lastAppliedFxMoveTicket,
                        lastCanceledTicket = synth.lastCanceledFxMoveTicket,
                    ) { index, config -> synth.setFxSlot(index, config) }
                },
            )
            if (!settled) return@async null
            repository.saveCurrentAs("Rerouted Echo")
        }
        delay(25L)
        assertFalse("The save must not capture an unacknowledged move", save.isCompleted)

        // Simulates the same AndroidAudioOutput render callback that runs on
        // its dedicated AudioTrack worker; note playback is not needed to drain.
        synth.renderStereo(FloatArray(256), 128)
        val outcome = withTimeout(2_000L) { save.await() }
        val saved = when (outcome) {
            is PatchOutcome.Success -> outcome.value.patch
            else -> throw AssertionError("The settled rack should save successfully")
        }
        assertEquals(otherChorus, saved.fxSlots[0])
        assertEquals(editedDelay, saved.fxSlots[1])
        assertEquals(editedDelay, synth.fxSlot(1))
        assertEquals(otherChorus, synth.fxSlot(0))
        assertEquals(editedDelay, PatchCodec.decode(PatchCodec.encode(saved)).fxSlots[1])
        assertFalse(coordinator.hasPendingMove)
    }

    @Test fun stoppedAudioWorkerCannotCausePrematureFxPatchSave() = runBlocking {
        val synth = OrbitalSynth(sampleRate = 48_000, maxVoices = 4)
        synth.setFxSlot(0, oldDelay)
        synth.setFxSlot(1, otherChorus)
        val coordinator = FxRackHostReconciler()
        val ticket = synth.requestFxSlotMove(0, 1)
        val visible = coordinator.stageMove(
            List(synth.fxSlotCount) { synth.fxSlot(it) }, 0, 1, ticket,
        )
        val settled = awaitFxRackSettled(
            timeoutMillis = 72L,
            isPending = { coordinator.hasPendingMove },
            reconcile = {
                coordinator.reconcile(
                    visible,
                    List(synth.fxSlotCount) { synth.fxSlot(it) },
                    synth.lastAppliedFxMoveTicket,
                    synth.lastCanceledFxMoveTicket,
                ) { _, _ -> error("No FX changes should be published") }
            },
        )
        assertFalse(settled)
        assertTrue(coordinator.hasPendingMove)
        assertEquals(oldDelay, synth.fxSlot(0))
    }
}
