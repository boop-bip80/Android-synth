package com.orbital.synth.audio

/**
 * One bounded recovery attempt for an unexpected render-worker failure.
 *
 * AndroidAudioOutput owns this state under lifecycleLock. A stale worker may
 * never revive playback after Activity stop, focus loss, teardown or a newer
 * foreground generation. A second fault must surface as a terminal error,
 * rather than opening AudioTracks in an unbounded crash loop.
 */
internal class AudioFailureRecoveryPolicy {
    private var retryConsumed = false

    fun claimRetry(
        unexpectedFailure: Boolean,
        generationMatches: Boolean,
        playbackRequested: Boolean,
        awaitingFocusGain: Boolean,
        disposed: Boolean,
    ): Boolean {
        if (retryConsumed || !unexpectedFailure || !generationMatches ||
            !playbackRequested || awaitingFocusGain || disposed) return false
        retryConsumed = true
        return true
    }

    /** A later explicit foreground session gets one fresh attempt. */
    fun resetOnExplicitStop() {
        retryConsumed = false
    }
}
