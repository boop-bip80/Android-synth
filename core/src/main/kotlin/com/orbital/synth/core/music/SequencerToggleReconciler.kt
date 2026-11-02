package com.orbital.synth.core.music

/**
 * Reconciles optimistic step toggles against worker-published sequencer state.
 *
 * Each accepted toggle gets a monotonically increasing ticket from the audio
 * worker's command queue. Until that ticket is acknowledged, an older snapshot
 * must not replace the user's latest tap (including rapid OFF -> ON pairs).
 *
 * All methods are called from the UI thread; no audio-thread locks are used.
 */
class SequencerToggleReconciler {
    private data class Intent(val enabled: Boolean, val ticket: Long)

    private val pending = mutableMapOf<Int, Intent>()
    private var revision: Long? = null

    fun record(selectionRevision: Long, step: Int, enabled: Boolean, ticket: Long) {
        require(step >= 0) { "Step index must not be negative" }
        require(ticket > 0L) { "Toggle ticket must be positive" }
        if (revision != selectionRevision) {
            pending.clear()
            revision = selectionRevision
        }
        pending[step] = Intent(enabled, ticket)
    }

    /** A newer note edit for this step supersedes its earlier toggle. */
    fun clearStep(step: Int) {
        pending.remove(step)
    }

    fun clear() {
        pending.clear()
        revision = null
    }

    /**
     * Produce the current UI/save state. Applied tickets have already been
     * reflected by the authoritative worker snapshot and may be discarded.
     * Unacknowledged taps always win over older snapshots.
     */
    fun reconcile(
        selectionRevision: Long,
        appliedToggleId: Long,
        workerEnabled: Set<Int>,
        stepCount: Int,
    ): Set<Int> {
        require(stepCount in 1..64) { "Step count must be 1..64" }
        if (revision != selectionRevision) {
            pending.clear()
            revision = selectionRevision
        }
        pending.entries.removeAll { (step, intent) ->
            step !in 0 until stepCount || intent.ticket <= appliedToggleId
        }
        if (pending.isEmpty()) return workerEnabled
        return workerEnabled.toMutableSet().apply {
            pending.forEach { (step, intent) ->
                if (intent.enabled) add(step) else remove(step)
            }
        }
    }
}
