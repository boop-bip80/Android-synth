package com.orbital.synth.audio

import com.orbital.synth.core.music.NoteEventKind
import com.orbital.synth.core.music.TimedNoteEvent
import com.orbital.synth.core.music.TimedNoteSink
import com.orbital.synth.core.performance.ArpeggiatorDirection
import com.orbital.synth.core.performance.ArpeggiatorSettings
import com.orbital.synth.core.performance.PerformanceArpeggiator
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Audio-worker owner of the arpeggiator, including direct-play bypass.
 *
 * UI/MIDI threads only enqueue messages; the worker is the sole owner of the
 * MIDI-source key table, arpeggiator, and render segmentation. A distinct source
 * (-7) is used for generated notes, never the touch (-1), sequencer (-2), or
 * fallback MIDI (-3) source. No locks or object allocations occur per audio sample.
 *
 * The current sample clock is scoped to arpeggiator phrases. When enabled, each
 * note event is applied before rendering its exact sample; nested rendering in
 * AudioSequencerTransport is supported without compromising sequencer timing.
 */
internal class PerformanceArpTransport(
    sampleRate: Int,
    private val renderFrames: (FloatArray, Int) -> Unit,
    private val noteOn: (Long, Int, Int, Float) -> Unit,
    private val noteOff: (Long, Int, Int) -> Unit,
    // Optional deterministic test seam after epoch check, before offer; never per sample.
    private val beforeInputEnqueue: (() -> Unit)? = null,
    // Count only physical key/source commands. Safety fallback releases all keys
    // if their producer outruns the audio worker for multiple render buffers.
    private val maxPendingPhysicalCommands: Int = 2_048,
    // Settings/Enabled are producer-side UI edits. Keep their ordinary FIFO
    // semantics up to this limit; excess edits collapse to the latest snapshot.
    private val maxPendingControlCommands: Int = 32,
    // Optional deterministic producer seam after immutable publication and
    // before the queue offer; tests can reproduce delayed old-worker controls.
    private val beforeControlEnqueue: (() -> Unit)? = null,
    // Test-only race seam; invoked on worker only when overflow was observed.
    private val beforeOverflowSnapshot: (() -> Unit)? = null,
) {
    init {
        require(maxPendingPhysicalCommands > 0) { "Input queue capacity must be positive" }
        require(maxPendingControlCommands > 0) { "Control queue capacity must be positive" }
    }
    private sealed interface Command {
        data class Enabled(val value: Boolean, val revision: Long) : Command
        data class Settings(val value: ArpeggiatorSettings, val revision: Long) : Command
        data class InputOn(
            val source: Long, val channel: Int, val note: Int,
            val velocity: Int, val epoch: Long
        ) : Command
        data class InputOff(
            val source: Long, val channel: Int, val note: Int, val epoch: Long
        ) : Command
        data class SourceOff(val source: Long, val epoch: Long) : Command
    }

    private data class Key(val source: Long, val channel: Int, val note: Int)

    // One immutable publication keeps settings, enable state and revisions
    // consistent across UI producers, stop/start, and worker reconciliations.
    // Revisions are independent: an intervening settings edit must not make
    // an older but still-current enabled command disappear (and vice versa).
    private data class RequestedControls(
        val enabled: Boolean = false,
        val settings: ArpeggiatorSettings = ArpeggiatorSettings(),
        val enabledRevision: Long = 0L,
        val settingsRevision: Long = 0L,
    )

    private val commands = ConcurrentLinkedQueue<Command>()
    // Odd epochs accept input, even epochs reject it. A queued physical command
    // from an earlier worker can NEVER be consumed by a newer worker, even if
    // the producer was preempted after checking isRunning but before queue.offer.
    private val inputEpoch = AtomicLong(0L)
    private val pendingPhysicalCommands = AtomicInteger(0)
    private val pendingControlCommands = AtomicInteger(0)
    private val controlOverflowDirty = AtomicBoolean(false)
    private val editLock = Any()

    // Exposed only within this module for deterministic stress assertions.
    internal val pendingPhysicalInputCount: Int get() = pendingPhysicalCommands.get()
    internal val pendingControlCommandCount: Int get() = pendingControlCommands.get()
    internal val appliedSettingsControlRevision: Long get() = workerSettingsRevision
    internal val requestedSettingsControlRevision: Long get() = requestedControls.settingsRevision
    internal val appliedEnabledControlRevision: Long get() = workerEnabledRevision
    internal val requestedEnabledControlRevision: Long get() = requestedControls.enabledRevision
    @Volatile private var requestedControls = RequestedControls()
    private val arpeggiator = PerformanceArpeggiator(sampleRate)
    private val directKeys = LinkedHashMap<Key, Int>()

    // Reusable event callback. AudioTrack may split one write at STEP boundaries,
    // so never create event Lists or captured lambdas in those nested fragments.
    // Only the render worker mutates these fields; no synchronization is needed.
    private var eventOutput = FloatArray(0)
    private var eventScratch = FloatArray(0)
    private var eventStart = 0L
    private var eventFrames = 0
    private var eventRendered = 0
    private val generatedSink = TimedNoteSink { atSample, kind, midiNote, velocity ->
        val offset = (atSample - eventStart).toInt()
        require(offset in eventRendered until eventFrames) {
            "Arpeggiator must emit sorted events inside the current block"
        }
        if (offset > eventRendered) {
            renderSegment(eventOutput, eventScratch, eventRendered,
                offset - eventRendered, eventFrames)
            eventRendered = offset
        }
        when (kind) {
            NoteEventKind.NOTE_ON ->
                noteOn(ARP_SOURCE_ID, 0, midiNote, velocity.coerceIn(1, 127) / 127f)
            NoteEventKind.NOTE_OFF -> noteOff(ARP_SOURCE_ID, 0, midiNote)
        }
    }

    // All variables below are confined to the audio worker.
    private var workerEnabled = false
    private var workerEnabledRevision = 0L
    private var workerSettingsRevision = 0L

    val enabled: Boolean get() = requestedControls.enabled
    val direction: ArpeggiatorDirection get() = requestedControls.settings.direction
    val subdivisionsPerBeat: Int get() = requestedControls.settings.subdivisionsPerBeat
    val tempo: Int get() = requestedControls.settings.bpm.toInt()
    val latch: Boolean get() = requestedControls.settings.latch
    val octaves: Int get() = requestedControls.settings.octaves
    val ratchets: Int get() = requestedControls.settings.ratchets
    val gate: Double get() = requestedControls.settings.gate
    val probability: Double get() = requestedControls.settings.probability

    fun setEnabled(value: Boolean) {
        synchronized(editLock) {
            val previous = requestedControls
            val next = previous.copy(enabled = value,
                enabledRevision = previous.enabledRevision + 1L)
            requestedControls = next
            enqueueControl { Command.Enabled(value, next.enabledRevision) }
        }
    }

    fun setDirection(value: ArpeggiatorDirection) {
        updateSettings { it.copy(direction = value) }
    }

    /** 1 = quarter notes, 2 = eighth notes, 4 = sixteenths. */
    fun setRate(subdivisionsPerBeat: Int): Boolean {
        if (subdivisionsPerBeat !in 1..16) return false
        updateSettings { it.copy(subdivisionsPerBeat = subdivisionsPerBeat) }
        return true
    }

    fun setTempo(bpm: Int): Boolean {
        if (bpm !in 20..300) return false
        updateSettings { it.copy(bpm = bpm.toDouble()) }
        return true
    }

    fun setLatch(value: Boolean) {
        updateSettings { it.copy(latch = value) }
    }

    fun setSwing(value: Double): Boolean {
        if (!value.isFinite() || value !in 0.0..1.0) return false
        updateSettings { it.copy(swing = value) }
        return true
    }

    fun setOctaves(value: Int): Boolean {
        if (value !in 1..4) return false
        updateSettings { it.copy(octaves = value) }
        return true
    }

    fun setRatchets(value: Int): Boolean {
        if (value !in 1..8) return false
        updateSettings { it.copy(ratchets = value) }
        return true
    }

    fun setGate(value: Double): Boolean {
        if (!value.isFinite() || value !in 0.05..1.0) return false
        updateSettings { it.copy(gate = value) }
        return true
    }

    fun setProbability(value: Double): Boolean {
        if (!value.isFinite() || value !in 0.0..1.0) return false
        updateSettings { it.copy(probability = value) }
        return true
    }

    private fun updateSettings(change: (ArpeggiatorSettings) -> ArpeggiatorSettings) {
        // Only producer threads take this lock; the audio thread never does.
        synchronized(editLock) {
            val previous = requestedControls
            val updated = change(previous.settings)
            val next = previous.copy(settings = updated,
                settingsRevision = previous.settingsRevision + 1L)
            requestedControls = next
            enqueueControl { Command.Settings(updated, next.settingsRevision) }
        }
    }

    private inline fun enqueueControl(command: () -> Command) {
        // A test may pause here after publishing a new desired configuration.
        // An old worker's delayed offer must not reconfigure the next worker.
        try {
            beforeControlEnqueue?.invoke()
        } catch (error: Throwable) {
            // The immutable request was already published; retain a bounded
            // worker notification even if the producer's test seam throws.
            controlOverflowDirty.set(true)
            throw error
        }
        // All control producers hold editLock; only the worker decrements.
        // No CAS loop, queue scan, worker lock, or per-sample allocation.
        if (pendingControlCommands.get() >= maxPendingControlCommands) {
            controlOverflowDirty.set(true)
            return
        }
        pendingControlCommands.incrementAndGet()
        var enqueued = false
        try {
            commands.offer(command())
            enqueued = true
        } finally {
            if (!enqueued) {
                pendingControlCommands.decrementAndGet()
                controlOverflowDirty.set(true)
            }
        }
    }

    /**
     * All physical notes should enter here, even with arp disabled: this permits
     * us to release only the right MIDI source on mode switch or disconnect.
     */
    fun inputNoteOn(source: Long, channel: Int, note: Int, velocity: Int = 127): Boolean {
        if (channel !in 0..15 || note !in 0..127 || velocity !in 0..127) return false
        if (velocity == 0) return inputNoteOff(source, channel, note)
        val epoch = activeInputEpoch() ?: return false
        return offerPhysicalCommand(epoch) {
            Command.InputOn(source, channel, note, velocity, epoch)
        }
    }

    fun inputNoteOff(source: Long, channel: Int, note: Int): Boolean {
        if (channel !in 0..15 || note !in 0..127) return false
        val epoch = activeInputEpoch() ?: return false
        return offerPhysicalCommand(epoch) { Command.InputOff(source, channel, note, epoch) }
    }

    fun inputSourceAllNotesOff(source: Long) {
        val epoch = activeInputEpoch() ?: return
        offerPhysicalCommand(epoch) { Command.SourceOff(source, epoch) }
    }

    /**
     * Every physical event reserves exactly one slot before it can be offered.
     * Release events are NEVER silently evicted: on saturation the epoch becomes
     * even (closed), and the audio worker performs a global panic next buffer.
     * This exceptional overload path may silence OTHER sources; ordinary
     * source/channel ownership and note-offs remain unchanged below capacity.
     */
    private inline fun offerPhysicalCommand(epoch: Long, command: () -> Command): Boolean {
        if (!reservePhysicalSlot(epoch)) return false
        var enqueued = false
        try {
            beforeInputEnqueue?.invoke()
            // The producer may have been preempted by stop or overload.
            if (inputEpoch.get() != epoch) return false
            commands.offer(command())
            enqueued = true
            // Even a late offer after another producer overloaded this epoch
            // is harmless: the worker discards all events with that old epoch.
            return inputEpoch.get() == epoch
        } finally {
            if (!enqueued) pendingPhysicalCommands.decrementAndGet()
        }
    }

    private fun reservePhysicalSlot(epoch: Long): Boolean {
        while (inputEpoch.get() == epoch) {
            val pending = pendingPhysicalCommands.get()
            if (pending >= maxPendingPhysicalCommands) {
                // Single compare-and-set closes input immediately, even when a
                // producer is preempted. The worker sees this even epoch and
                // releases every held key before reopening a NEW epoch.
                inputEpoch.compareAndSet(epoch, epoch + 1L)
                return false
            }
            if (pendingPhysicalCommands.compareAndSet(pending, pending + 1)) {
                if (inputEpoch.get() == epoch) return true
                pendingPhysicalCommands.decrementAndGet()
                return false
            }
        }
        return false
    }

    private fun forgetCommandReservation(command: Command) {
        when (command) {
            is Command.InputOn, is Command.InputOff, is Command.SourceOff ->
                pendingPhysicalCommands.decrementAndGet()
            is Command.Enabled, is Command.Settings ->
                pendingControlCommands.decrementAndGet()
        }
    }

    fun panic() {
        // Safety is independent of queue length: immediately close the current
        // odd input epoch so the NEXT audio block releases every held key.
        // Pending NoteOn/NoteOff from this epoch are rejected as stale, and
        // the worker safely reopens a new epoch after its global release.
        deactivateInputEpoch()
    }

    private fun activeInputEpoch(): Long? {
        val epoch = inputEpoch.get()
        return if (epoch > 0L && (epoch and 1L) != 0L) epoch else null
    }

    /**
     * Arm the next worker's physical-input epoch AFTER AudioTrack opens and
     * AFTER the old worker completed onWorkerStop(), but BEFORE Thread.start().
     * This is a lock-free CAS only: no DSP, queue drain or render allocation.
     * Pre-start contacts are consumed exactly once by the first renderBlock().
     */
    fun armInputForPendingWorker() = activateInputEpoch()

    /**
     * Thread.start() can fail before any render worker owns this transport.
     * Close producer admission FIRST, then drain pending commands with exactly
     * the same reservation accounting as onWorkerStop(). Never call worker-
     * owned DSP cleanup from this lifecycle-failure path.
     */
    fun cancelPendingWorkerInput() {
        deactivateInputEpoch()
        while (true) {
            val command = commands.poll() ?: break
            forgetCommandReservation(command)
        }
    }

    /** Called on worker lifecycle only; MIDI and UI producer paths never lock. */
    private fun activateInputEpoch() {
        while (true) {
            val observed = inputEpoch.get()
            if ((observed and 1L) != 0L) return
            if (inputEpoch.compareAndSet(observed, observed + 1L)) return
        }
    }

    private fun deactivateInputEpoch() {
        while (true) {
            val observed = inputEpoch.get()
            if ((observed and 1L) == 0L) return
            if (inputEpoch.compareAndSet(observed, observed + 1L)) return
        }
    }

    fun onWorkerStart() {
        // WorkerStop already discarded old physical-key events. Preserve freshly
        // enqueued UI note presses made during worker startup.
        val controls = requestedControls
        releaseGenerated(arpeggiator.panic())
        releaseDirect()
        releaseGenerated(arpeggiator.configure(controls.settings))
        workerSettingsRevision = controls.settingsRevision
        workerEnabled = controls.enabled
        workerEnabledRevision = controls.enabledRevision
        if (workerEnabled) arpeggiator.start()
        activateInputEpoch()
    }

    fun onWorkerStop() {
        // Invalidate old commands BEFORE clearing the queue. A delayed producer
        // may enqueue afterward but its stale epoch will be rejected on restart.
        deactivateInputEpoch()
        releaseGenerated(arpeggiator.panic())
        releaseDirect()
        workerEnabled = false
        // A focus-loss/resume must not play stale NoteOn events sent before loss.
        // Account for reservations while draining, including concurrent producers
        // that may still be paused BEFORE offer; those release in their finally.
        // Requested controls remain available as immutable volatile snapshots.
        while (true) {
            val command = commands.poll() ?: break
            forgetCommandReservation(command)
        }
    }

    fun renderBlock(output: FloatArray, scratch: FloatArray, frames: Int) {
        require(frames >= 0 && frames * 2 <= output.size && frames * 2 <= scratch.size)
        drainCommands()
        if (frames == 0) return
        if (!workerEnabled || !arpeggiator.isPlaying) {
            renderFrames(output, frames)
            return
        }

        eventOutput = output
        eventScratch = scratch
        eventStart = arpeggiator.cursorSample
        eventFrames = frames
        eventRendered = 0
        arpeggiator.advanceInto(frames, generatedSink)
        if (eventRendered < frames) {
            renderSegment(output, scratch, eventRendered, frames - eventRendered, frames)
        }
    }

    private fun drainCommands() {
        // Both physical saturation and panic close the active odd epoch
        // immediately. This always releases held voices before considering
        // queued controls or physical input and cannot be starved by UI floods.
        val retiredEpoch = inputEpoch.get()
        if (retiredEpoch > 0L && (retiredEpoch and 1L) == 0L) {
            releaseGenerated(arpeggiator.panic())
            releaseDirect()
            if (workerEnabled) arpeggiator.start()
            inputEpoch.compareAndSet(retiredEpoch, retiredEpoch + 1L)
        }

        // Normal (< maxPendingControlCommands) control messages retain their
        // exact original FIFO placement alongside physical note events.
        for (ignored in 0 until 512) {
            val command = commands.poll() ?: break
            forgetCommandReservation(command)
            when (command) {
                is Command.Enabled -> {
                    if (command.value != requestedControls.enabled) continue
                    applyEnabledControl(command.value, command.revision)
                }
                is Command.Settings -> {
                    if (command.value != requestedControls.settings) continue
                    applySettingsControl(command.value, command.revision)
                }
                is Command.InputOn -> {
                    if (command.epoch != inputEpoch.get()) continue
                    if (workerEnabled) {
                        arpeggiator.noteOn(
                            command.source, command.channel, command.note, command.velocity
                        )
                    } else {
                        val key = Key(command.source, command.channel, command.note)
                        directKeys[key] = (directKeys[key] ?: 0) + 1
                        noteOn(
                            command.source, command.channel, command.note,
                            command.velocity / 127f
                        )
                    }
                }
                is Command.InputOff -> {
                    if (command.epoch != inputEpoch.get()) continue
                    if (workerEnabled) {
                        releaseGenerated(arpeggiator.noteOff(
                            command.source, command.channel, command.note
                        ))
                    } else {
                        val key = Key(command.source, command.channel, command.note)
                        val count = directKeys[key] ?: 0
                        if (count > 0) {
                            noteOff(command.source, command.channel, command.note)
                            if (count == 1) directKeys.remove(key)
                            else directKeys[key] = count - 1
                        }
                    }
                }
                is Command.SourceOff -> {
                    if (command.epoch != inputEpoch.get()) continue
                    releaseDirectSource(command.source)
                    releaseGenerated(arpeggiator.allNotesOff(command.source))
                }
            }
        }

        // Overflow-only last-writer-wins reconciliation occurs at the END of
        // this worker segment. At most 32 stale UI controls can precede a real
        // NoteOff; 100k slider changes cannot monopolize 512-command drains.
        // getAndSet precedes the snapshot: a simultaneous producer either
        // appears in this snapshot or leaves a dirty flag for the next block.
        if (controlOverflowDirty.getAndSet(false)) {
            beforeOverflowSnapshot?.invoke()
            val latest = requestedControls
            applySettingsControl(latest.settings, latest.settingsRevision)
            applyEnabledControl(latest.enabled, latest.enabledRevision)
        }
    }

    private fun applySettingsControl(value: ArpeggiatorSettings, revision: Long) {
        // onWorkerStart already applied its immutable snapshot. A producer
        // delayed across stop/start may offer an old control afterward.
        if (revision <= workerSettingsRevision) return
        workerSettingsRevision = revision
        releaseGenerated(arpeggiator.configure(value))
    }

    private fun applyEnabledControl(value: Boolean, revision: Long) {
        if (revision <= workerEnabledRevision) return
        workerEnabledRevision = revision
        if (workerEnabled == value) return
        if (value) {
            releaseDirect()
            releaseGenerated(arpeggiator.panic())
            releaseGenerated(arpeggiator.configure(requestedControls.settings))
            arpeggiator.start()
            workerEnabled = true
        } else {
            releaseGenerated(arpeggiator.panic())
            workerEnabled = false
        }
    }

    private fun releaseGenerated(events: List<TimedNoteEvent>) {
        for (event in events) {
            if (event.kind == NoteEventKind.NOTE_OFF) {
                noteOff(ARP_SOURCE_ID, 0, event.midiNote)
            }
        }
    }

    private fun releaseDirectSource(source: Long) {
        val iterator = directKeys.iterator()
        while (iterator.hasNext()) {
            val (key, count) = iterator.next()
            if (key.source != source) continue
            repeat(count) { noteOff(key.source, key.channel, key.note) }
            iterator.remove()
        }
    }

    private fun releaseDirect() {
        for ((key, count) in directKeys) {
            repeat(count) { noteOff(key.source, key.channel, key.note) }
        }
        directKeys.clear()
    }

    private fun renderSegment(
        output: FloatArray, scratch: FloatArray,
        start: Int, frames: Int, totalFrames: Int
    ) {
        if (start == 0 && frames == totalFrames) {
            renderFrames(output, frames)
        } else {
            renderFrames(scratch, frames)
            System.arraycopy(scratch, 0, output, start * 2, frames * 2)
        }
    }

    companion object {
        const val ARP_SOURCE_ID: Long = -7L
    }
}
