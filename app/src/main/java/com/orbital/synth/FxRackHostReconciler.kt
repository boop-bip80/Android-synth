package com.orbital.synth

import com.orbital.synth.core.dsp.processing.FxModuleConfig

/**
 * Main-thread-owned optimistic rack view across audio-worker move events.
 *
 * OrbitalSynth publishes a request ticket when an FX reordering is queued, and
 * acknowledges it after the render-worker has moved both the immutable slot
 * configuration and the processor's complete internal state. A live touch edit
 * made before this acknowledgment must NOT be sent to the old index.
 *
 * The expected baseline follows every queued move, while the visible snapshot
 * also carries any deferred slider/bypass edits. Once the final move ticket has
 * settled, only values that diverge from that baseline are published to DSP.
 * Identical adjacent module configurations are safe because we use a worker
 * ticket, not a brittle list-equality guess, to detect move completion.
 *
 * A patch recall may cancel moves. Its canceled ticket frontier takes
 * precedence over an applied frontier so stale UI edits cannot contaminate the
 * newly recalled patch.
 *
 * All methods are called on the Android main thread; audio code never touches
 * this object or locks on its state.
 */
internal class FxRackHostReconciler {
    private var awaitingTicket = 0L
    private var expectedAfterMove: List<FxModuleConfig>? = null

    val hasPendingMove: Boolean get() = awaitingTicket != 0L

    fun stageMove(
        visible: List<FxModuleConfig>,
        from: Int,
        to: Int,
        ticket: Long,
    ): List<FxModuleConfig> {
        if (ticket <= 0L || from !in visible.indices ||
            to !in visible.indices || from == to) return visible
        val baseline = expectedAfterMove ?: visible
        if (baseline.size != visible.size) {
            clear()
            return visible
        }
        expectedAfterMove = relocated(baseline, from, to)
        awaitingTicket = maxOf(awaitingTicket, ticket)
        return relocated(visible, from, to)
    }

    /**
     * Publish immediately if no move is pending; otherwise retain this edit
     * optimistically and defer DSP submission until its new slot index is real.
     */
    fun stageEdit(
        visible: List<FxModuleConfig>,
        index: Int,
        next: FxModuleConfig,
        publish: (Int, FxModuleConfig) -> Unit,
    ): List<FxModuleConfig> {
        if (index !in visible.indices ||
            !next.wet.isFinite() || !next.depth.isFinite() ||
            !next.rate.isFinite() || !next.stereo.isFinite()) return visible
        if (!hasPendingMove) publish(index, next)
        return visible.toMutableList().apply { set(index, next) }
    }

    fun reconcile(
        visible: List<FxModuleConfig>,
        actual: List<FxModuleConfig>,
        lastAppliedTicket: Long,
        lastCanceledTicket: Long,
        publish: (Int, FxModuleConfig) -> Unit,
    ): List<FxModuleConfig> {
        if (!hasPendingMove) return actual
        if (visible.size != actual.size) {
            clear()
            return actual
        }
        if (awaitingTicket <= lastCanceledTicket) {
            clear()
            return actual
        }
        if (awaitingTicket > lastAppliedTicket) return visible

        val baseline = expectedAfterMove ?: actual
        val next = actual.toMutableList()
        for (index in actual.indices) {
            // Compare only editable changes, not stable slot movement itself.
            if (visible[index] != baseline[index]) {
                publish(index, visible[index])
                next[index] = visible[index]
            }
        }
        clear()
        return next
    }

    fun clear() {
        awaitingTicket = 0L
        expectedAfterMove = null
    }

    private fun relocated(
        slots: List<FxModuleConfig>,
        from: Int,
        to: Int,
    ): List<FxModuleConfig> =
        slots.toMutableList().apply { add(to, removeAt(from)) }
}


/**
 * Suspend the *save coroutine*, never Android's UI or AudioTrack render thread.
 * The regular 32 ms Activity poll also calls reconcile(); this bounded fast
 * poll merely prevents V4 patch capture from racing a pending render-worker
 * FX move and its deferred slider edits.
 *
 * If audio is stopped the ticket cannot settle; return false and show honest
 * feedback instead of silently persisting the wrong FX order.
 */
internal suspend fun awaitFxRackSettled(
    timeoutMillis: Long,
    isPending: () -> Boolean,
    reconcile: () -> Unit,
): Boolean {
    if (!isPending()) return true
    return kotlinx.coroutines.withTimeoutOrNull(timeoutMillis.coerceAtLeast(1L)) {
        while (isPending()) {
            reconcile()
            if (isPending()) kotlinx.coroutines.delay(16L)
        }
        true
    } ?: false
}
