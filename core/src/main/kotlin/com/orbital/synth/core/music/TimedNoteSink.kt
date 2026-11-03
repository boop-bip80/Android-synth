package com.orbital.synth.core.music

/**
 * Primitive sample-addressed note-event delivery for a single audio worker.
 *
 * Callers allocate one sink alongside their renderer and reuse it for every
 * block. No event objects, arrays, lambdas or iterators are created by emitting
 * a note. The callback must not recursively advance the same sequencer/arp.
 *
 * [sampleTime] is absolute and belongs to the half-open current render block;
 * [velocity] is 1..127 on NOTE_ON and zero on NOTE_OFF.
 */
fun interface TimedNoteSink {
    fun emit(sampleTime: Long, kind: NoteEventKind, midiNote: Int, velocity: Int)
}
