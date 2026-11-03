package com.orbital.synth.audio

/**
 * Resolves MIDI 1.0 pitch bend for a synth with one GLOBAL bend destination.
 *
 * Each physical/virtual output port keeps its own last value per MIDI channel.
 * The last received bend controls the engine. If that port disconnects, restore
 * the latest value from a surviving port, or MIDI center when none remains.
 *
 * [deliver] must be a quick, nonblocking command to the synth's control queue.
 * It is intentionally invoked under the same lock as state changes: otherwise
 * a delayed callback from a closing port could overwrite the newer reset.
 * No work here executes on the audio-render thread.
 */
internal class MidiSourcePitchBendOwnership(private val deliver: (Int) -> Unit) {
    private data class PortChannel(val sourceId: Long, val channel: Int)
    private data class BendEvent(val value: Int, val revision: Long)

    private val liveBends = mutableMapOf<PortChannel, BendEvent>()
    private var nextRevision = 0L
    private var winner: PortChannel? = null

    @Synchronized
    fun onBend(sourceId: Long, channel: Int, value: Int) {
        require(sourceId > 0L) { "MIDI port source ID must be positive" }
        require(channel in 0..15) { "MIDI channel must be 0..15" }
        val key = PortChannel(sourceId, channel)
        val clamped = value.coerceIn(0, 16_383)
        liveBends[key] = BendEvent(clamped, ++nextRevision)
        winner = key
        deliver(clamped)
    }

    /**
     * MIDI CC121 resets this port/channel's bend to center. The reset is the
     * latest expression event for our global-bend synth, but the saved bends
     * of other connected ports remain available if this port disconnects.
     */
    fun onControllerReset(sourceId: Long, channel: Int) {
        onBend(sourceId, channel, 8_192)
    }

    @Synchronized
    fun onDisconnect(sourceId: Long) {
        require(sourceId > 0L) { "MIDI port source ID must be positive" }
        val wasWinner = winner?.sourceId == sourceId
        val iterator = liveBends.keys.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().sourceId == sourceId) iterator.remove()
        }
        // A noncontrolling device must never reset another connected
        // controller's current expression.
        if (!wasWinner) return
        val replacement = liveBends.maxByOrNull { it.value.revision }
        winner = replacement?.key
        deliver(replacement?.value?.value ?: 8_192)
    }
}
