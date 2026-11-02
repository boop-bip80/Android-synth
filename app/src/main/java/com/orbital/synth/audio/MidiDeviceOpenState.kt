package com.orbital.synth.audio

/**
 * Session-local lifecycle for Android MIDI device openings.
 *
 * Android may deliver an openDevice callback after a device has been removed
 * and another device with the same framework ID has already been added.
 * A device ID by itself is therefore insufficient to authorize an attach.
 *
 * All methods are called while AndroidMidiManager's lock is held. The epochs
 * are bookkeeping only: no Android classes, clocks or background threads are
 * required, which keeps the race testable on the JVM.
 */
internal class MidiDeviceOpenState {
    private val epochs = mutableMapOf<Int, Long>()
    private val opening = mutableMapOf<Int, Long>()
    private val detached = mutableSetOf<Int>()
    private var nextEpoch = 1L

    fun onAttached(deviceId: Int) {
        detached.remove(deviceId)
    }

    fun onDetached(deviceId: Int) {
        // Invalidate every in-flight callback for this ID. A new onAdded
        // may reopen the same framework ID without accepting the old handle.
        epochs[deviceId] = nextEpoch++
        detached.add(deviceId)
        opening.remove(deviceId)
    }

    /** Returns an opening token only when no connection/open is active. */
    fun beginOpen(deviceId: Int, connected: Boolean): Long? {
        if (connected || deviceId in detached || deviceId in opening) return null
        val epoch = epochs[deviceId] ?: 0L
        opening[deviceId] = epoch
        return epoch
    }

    /** A stale callback must not attach, even if its ID has been re-added. */
    fun isCurrentOpening(deviceId: Int, epoch: Long, connected: Boolean): Boolean =
        !connected &&
            deviceId !in detached &&
            opening[deviceId] == epoch &&
            (epochs[deviceId] ?: 0L) == epoch

    /** A stale completion must not clear a newer open's token. */
    fun finishOpen(deviceId: Int, epoch: Long) {
        if (opening[deviceId] == epoch) opening.remove(deviceId)
    }

    fun clear() {
        epochs.clear()
        opening.clear()
        detached.clear()
        nextEpoch = 1L
    }
}
