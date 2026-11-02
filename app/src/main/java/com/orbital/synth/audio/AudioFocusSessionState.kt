package com.orbital.synth.audio

/**
 * Pure state machine for one foreground audio-focus session.
 *
 * AndroidAudioOutput serializes every transition and AudioManager request/abandon
 * under its lifecycle lock. A transient loss retains the focus claim so Android
 * can deliver AUDIOFOCUS_GAIN; an explicit stop or permanent loss gives it up.
 */
internal class AudioFocusSessionState {
    var playbackRequested: Boolean = false
        private set
    var awaitingGain: Boolean = false
        private set
    var hasClaim: Boolean = false
        private set

    fun requestPlayback() {
        playbackRequested = true
    }

    fun cancelPlayback() {
        playbackRequested = false
        awaitingGain = false
    }

    /** True when a current foreground claim should pause until focus returns. */
    fun transientLoss(): Boolean {
        if (!hasClaim || !playbackRequested) return false
        awaitingGain = true
        return true
    }

    fun permanentLoss() {
        cancelPlayback()
    }

    /** Consume the suspended state; only one focus-gain callback may resume it. */
    fun gain(): Boolean {
        if (!hasClaim) return false
        val resume = playbackRequested && awaitingGain
        awaitingGain = false
        return resume
    }

    /**
     * Retain focus while a worker exits if it is waiting for transient recovery
     * or a newer explicit start has already queued its replacement.
     */
    fun retainClaimAfterWorkerExit(restartQueued: Boolean): Boolean =
        playbackRequested && (awaitingGain || restartQueued)

    /** Idempotent acquire/release; call both under the owner's lifecycle lock. */
    fun acquireClaim(request: () -> Boolean): Boolean {
        if (hasClaim) return true
        if (!request()) return false
        hasClaim = true
        return true
    }

    fun abandonClaim(abandon: () -> Unit) {
        if (!hasClaim) return
        hasClaim = false
        abandon()
    }
}
