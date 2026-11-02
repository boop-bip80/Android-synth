package com.orbital.synth.audio

import com.orbital.synth.core.music.NoteEventKind
import com.orbital.synth.core.music.TimedNoteEvent
import com.orbital.synth.core.performance.ArpeggiatorDirection
import com.orbital.synth.core.performance.ArpeggiatorSettings
import com.orbital.synth.core.performance.PerformanceArpeggiator
import java.util.concurrent.ConcurrentLinkedQueue

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
) {
    private sealed interface Command {
        data class Enabled(val value: Boolean) : Command
        data class Settings(val value: ArpeggiatorSettings) : Command
        data class InputOn(
            val source: Long, val channel: Int, val note: Int, val velocity: Int
        ) : Command
        data class InputOff(val source: Long, val channel: Int, val note: Int) : Command
        data class SourceOff(val source: Long) : Command
        data object Panic : Command
    }

    private data class Key(val source: Long, val channel: Int, val note: Int)

    private val commands = ConcurrentLinkedQueue<Command>()
    private val editLock = Any()
    @Volatile private var requestedEnabled = false
    @Volatile private var requestedSettings = ArpeggiatorSettings()
    private val arpeggiator = PerformanceArpeggiator(sampleRate)
    private val directKeys = LinkedHashMap<Key, Int>()

    // All variables below are confined to the audio worker.
    private var workerEnabled = false

    val enabled: Boolean get() = requestedEnabled
    val direction: ArpeggiatorDirection get() = requestedSettings.direction
    val subdivisionsPerBeat: Int get() = requestedSettings.subdivisionsPerBeat
    val tempo: Int get() = requestedSettings.bpm.toInt()
    val latch: Boolean get() = requestedSettings.latch

    fun setEnabled(value: Boolean) {
        requestedEnabled = value
        commands.offer(Command.Enabled(value))
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

    private fun updateSettings(change: (ArpeggiatorSettings) -> ArpeggiatorSettings) {
        // Only producer threads take this lock; the audio thread never does.
        synchronized(editLock) {
            val updated = change(requestedSettings)
            requestedSettings = updated
            commands.offer(Command.Settings(updated))
        }
    }

    /**
     * All physical notes should enter here, even with arp disabled: this permits
     * us to release only the right MIDI source on mode switch or disconnect.
     */
    fun inputNoteOn(source: Long, channel: Int, note: Int, velocity: Int = 127): Boolean {
        if (channel !in 0..15 || note !in 0..127 || velocity !in 0..127) return false
        if (velocity == 0) return inputNoteOff(source, channel, note)
        commands.offer(Command.InputOn(source, channel, note, velocity))
        return true
    }

    fun inputNoteOff(source: Long, channel: Int, note: Int): Boolean {
        if (channel !in 0..15 || note !in 0..127) return false
        commands.offer(Command.InputOff(source, channel, note))
        return true
    }

    fun inputSourceAllNotesOff(source: Long) {
        commands.offer(Command.SourceOff(source))
    }

    fun panic() {
        commands.offer(Command.Panic)
    }

    fun onWorkerStart() {
        // WorkerStop already discarded old physical-key events. Preserve freshly
        // enqueued UI note presses made during worker startup.
        releaseGenerated(arpeggiator.panic())
        releaseDirect()
        releaseGenerated(arpeggiator.configure(requestedSettings))
        workerEnabled = requestedEnabled
        if (workerEnabled) arpeggiator.start()
    }

    fun onWorkerStop() {
        releaseGenerated(arpeggiator.panic())
        releaseDirect()
        workerEnabled = false
        // A focus-loss/resume must not play stale NoteOn events sent before loss.
        // Requested controls remain available as immutable volatile snapshots.
        commands.clear()
    }

    fun renderBlock(output: FloatArray, scratch: FloatArray, frames: Int) {
        require(frames >= 0 && frames * 2 <= output.size && frames * 2 <= scratch.size)
        drainCommands()
        if (frames == 0) return
        if (!workerEnabled || !arpeggiator.isPlaying) {
            renderFrames(output, frames)
            return
        }

        val blockStart = arpeggiator.cursorSample
        val events = arpeggiator.advance(frames)
        var rendered = 0
        for (event in events) {
            val offset = (event.sampleTime - blockStart).toInt()
            require(offset in rendered until frames) {
                "Arpeggiator must emit sorted events inside the current block"
            }
            if (offset > rendered) {
                renderSegment(output, scratch, rendered, offset - rendered, frames)
                rendered = offset
            }
            dispatchGenerated(event)
        }
        if (rendered < frames) {
            renderSegment(output, scratch, rendered, frames - rendered, frames)
        }
    }

    private fun drainCommands() {
        // Bound worst-case incoming MIDI bursts; leftovers drain next buffer.
        for (ignored in 0 until 512) {
            when (val command = commands.poll() ?: break) {
                is Command.Enabled -> {
                    if (workerEnabled == command.value) continue
                    if (command.value) {
                        releaseDirect()
                        releaseGenerated(arpeggiator.panic())
                        releaseGenerated(arpeggiator.configure(requestedSettings))
                        arpeggiator.start()
                        workerEnabled = true
                    } else {
                        releaseGenerated(arpeggiator.panic())
                        workerEnabled = false
                    }
                }
                is Command.Settings -> {
                    releaseGenerated(arpeggiator.configure(command.value))
                }
                is Command.InputOn -> {
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
                    releaseDirectSource(command.source)
                    releaseGenerated(arpeggiator.allNotesOff(command.source))
                }
                Command.Panic -> {
                    releaseGenerated(arpeggiator.panic())
                    releaseDirect()
                    if (workerEnabled) arpeggiator.start()
                }
            }
        }
    }

    private fun dispatchGenerated(event: TimedNoteEvent) {
        when (event.kind) {
            NoteEventKind.NOTE_ON ->
                noteOn(ARP_SOURCE_ID, 0, event.midiNote, event.velocity.coerceIn(1, 127) / 127f)
            NoteEventKind.NOTE_OFF ->
                noteOff(ARP_SOURCE_ID, 0, event.midiNote)
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
