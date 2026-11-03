package com.orbital.synth.ui

/**
 * UI-thread-only lease for the shared FOCUS visual and touch-modulation state.
 *
 * Pointer recognizers can be replaced while their old contacts are still held.
 * A late finally block must release its own note leases separately, but only the
 * current recognizer may neutralize the shared sources or darken the canvas.
 * Identity tokens avoid an ABA match when recognizers are repeatedly recreated.
 */
internal class StageTouchSessionOwner {
    internal class Token

    private var current: Token? = null

    fun begin(): Token = Token().also { current = it }

    fun finish(token: Token, resetSharedState: () -> Unit): Boolean {
        if (current !== token) return false
        // Revoke before the callback, so duplicate/reentrant completion cannot
        // clear a new lease acquired by a callback into the host.
        current = null
        resetSharedState()
        return true
    }
}
