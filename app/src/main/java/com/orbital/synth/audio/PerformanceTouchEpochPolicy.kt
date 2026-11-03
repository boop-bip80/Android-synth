package com.orbital.synth.audio

/**
 * Main-thread-only observer policy for a foreground AudioTrack resume.
 *
 * The UI already invalidated every old touch owner in Activity.onStop().
 * The expected synchronous worker-start event must therefore be ACKNOWLEDGED
 * immediately in onStart(), before an eager new finger can hit the keyboard.
 * Only the EXACT single start transition may be acknowledged: a pending
 * worker, missed focus loss, unexpected worker crash or fast replacement must
 * still be observed by the next UI poll and revoke stale owners.
 */
internal object PerformanceTouchEpochPolicy {
    fun acknowledgeExpectedStart(
        previouslyObserved: Long?,
        beforeStart: Long,
        afterStart: Long,
        accepted: Boolean,
        running: Boolean,
    ): Long? =
        if (previouslyObserved != null &&
            previouslyObserved == beforeStart &&
            accepted && running &&
            afterStart == beforeStart + 1L
        ) afterStart else previouslyObserved
}
