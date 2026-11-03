package com.orbital.synth.ui

/**
 * UI-thread-only note ownership for real chromatic performance.
 *
 * Every physical finger and TalkBack press gets a unique, monotonic token.
 * An old delayed TalkBack release can remove ONLY its own token, never a new
 * finger on the same pitch after panic, patch recall, octave change or disposal.
 *
 * The first owner snapshots the correct release callback for the same host that
 * received note-on. Later same-pitch owners share that voice until the last
 * token releases. No allocation or lock occurs on the DSP audio worker.
 */
internal class ReferenceKeybedNoteOwnership {
    private data class NoteGroup(
        var count: Int,
        val onRelease: (Int) -> Unit,
    )

    private val groups = HashMap<Int, NoteGroup>()
    private val ownerNote = HashMap<Long, Int>()
    private var nextOwner = 0L

    val activeNotes: Set<Int> get() = groups.keys.toSet()
    val activeOwnerCount: Int get() = ownerNote.size

    fun holds(note: Int): Boolean = groups.containsKey(note)

    fun acquire(
        note: Int,
        onAttack: () -> Unit,
        onRelease: (Int) -> Unit,
    ): Long {
        require(note in 0..127)
        val group = groups[note]
        if (group == null) {
            onAttack()
            groups[note] = NoteGroup(count = 1, onRelease = onRelease)
        } else {
            group.count++
        }
        // Explicit overflow handling preserves token uniqueness even in a
        // theoretical multi-year always-running session.
        if (nextOwner == Long.MAX_VALUE) {
            nextOwner = 0L
            while (ownerNote.containsKey(nextOwner + 1L)) nextOwner++
        }
        val owner = ++nextOwner
        ownerNote[owner] = note
        return owner
    }

    /** True iff this exact owner was still active. Stale releases are no-ops. */
    fun release(owner: Long): Boolean {
        val note = ownerNote.remove(owner) ?: return false
        val group = groups[note] ?: return false
        group.count--
        if (group.count <= 0) {
            groups.remove(note)
            group.onRelease(note)
        }
        return true
    }

    /** Return distinct voices released by the panic/disposal boundary. */
    fun releaseAll(): Int {
        val snapshots = groups.entries.toList()
        ownerNote.clear()
        groups.clear()
        snapshots.forEach { (note, group) -> group.onRelease(note) }
        return snapshots.size
    }
}
