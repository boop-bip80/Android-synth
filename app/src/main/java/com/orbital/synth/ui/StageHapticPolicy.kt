package com.orbital.synth.ui

/**
 * Low-cost guard for optional platform-provided tactile feedback.
 *
 * Haptics are not an audio waveform renderer, and no physical amplitude can be
 * guaranteed by an emulator. This policy only permits one OS-standard short
 * accent at a genuine new contact, with explicit user opt-in, device permission
 * and touch-exploration safeguards. Moves/releases never emit.
 */
internal object StageHapticPolicy {
    const val COOLDOWN_MS = 140L

    enum class Event { NOTE_DOWN, NOTE_MOVE, NOTE_UP, CONTROL_DRAG }

    fun shouldTrigger(
        optedIn: Boolean,
        systemEnabled: Boolean,
        viewEnabled: Boolean,
        touchExplorationEnabled: Boolean,
        event: Event,
        atMs: Long,
        previousAtMs: Long,
    ): Boolean {
        if (!optedIn || !systemEnabled || !viewEnabled || touchExplorationEnabled) return false
        if (event != Event.NOTE_DOWN || atMs < 0L) return false
        if (previousAtMs < 0L) return true
        if (atMs <= previousAtMs) return false
        return atMs - previousAtMs >= COOLDOWN_MS
    }
}