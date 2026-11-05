package com.orbital.synth.core.stage

import kotlin.math.floor

/** How musically "stable" a lane sounds relative to the key's tonic; drives its glow. */
enum class LaneRole { ROOT, THIRD, FIFTH, COLOR }

/**
 * A vertical ladder of in-key notes. Lane 0 is the lowest (bass) note and sits at
 * the BOTTOM of the screen; the highest lane is at the TOP. Because only scale
 * tones exist, every touch is guaranteed to be "in key".
 *
 * Y positions are normalized with 0 = top edge and 1 = bottom edge, matching
 * Android pointer coordinates divided by the view height.
 */
class DiatonicGrid(
    val key: StageKey,
    lowestOctave: Int = 2,
    octaves: Int = 3,
) {
    /** Scientific-pitch octave of the lowest tonic (C4 = middle C = MIDI 60). */
    val lowestOctave: Int
    val octaves: Int
    val baseMidi: Int
    val stepsPerOctave: Int = key.stepsPerOctave

    /** MIDI note of every lane, ascending. */
    val notes: IntArray
    val laneCount: Int

    init {
        val safeOctave = lowestOctave.coerceIn(0, 7)
        val base = (safeOctave + 1) * 12 + key.tonic
        // Never allow a lane above MIDI 127: shrink the span rather than clamp notes.
        var span = octaves.coerceIn(1, 4)
        while (span > 1 && base + span * 12 > 127) span--
        this.lowestOctave = safeOctave
        this.octaves = span
        this.baseMidi = base
        laneCount = span * stepsPerOctave + 1
        notes = IntArray(laneCount) { midiForStep(it) }
    }

    /** MIDI note for a scale step counted from the lowest tonic; may exceed the grid. */
    fun midiForStep(step: Int): Int {
        val octave = Math.floorDiv(step, stepsPerOctave)
        val index = Math.floorMod(step, stepsPerOctave)
        return baseMidi + octave * 12 + key.degrees[index]
    }

    fun midiForLane(lane: Int): Int = notes[lane.coerceIn(0, laneCount - 1)]

    /** Zero-based scale degree inside its octave (0 = tonic). */
    fun degreeIndex(lane: Int): Int = Math.floorMod(lane, stepsPerOctave)

    /** One-based scale degree number shown on the lane label. */
    fun degreeNumber(lane: Int): Int = degreeIndex(lane) + 1

    /** Octave number of the lane in scientific pitch notation. */
    fun octaveOf(lane: Int): Int = midiForLane(lane) / 12 - 1

    fun noteName(lane: Int): String = key.nameOf(midiForLane(lane))

    /** Note name with octave, e.g. "F♯3". */
    fun fullName(lane: Int): String = noteName(lane) + octaveOf(lane)

    fun role(lane: Int): LaneRole {
        val interval = Math.floorMod(midiForLane(lane) - key.tonic, 12)
        return when {
            interval == 0 -> LaneRole.ROOT
            interval == 7 -> LaneRole.FIFTH
            interval == 3 || interval == 4 -> LaneRole.THIRD
            else -> LaneRole.COLOR
        }
    }

    /** The lane band a vertical position falls in; bottom of screen = lane 0. */
    fun laneForY(yFromTop: Float): Int {
        val y = if (yFromTop.isFinite()) yFromTop.coerceIn(0f, 1f) else 0.5f
        return floor((1f - y) * laneCount).toInt().coerceIn(0, laneCount - 1)
    }

    /** Fractional lane coordinate where each lane's centre is an integer. */
    fun continuousLane(yFromTop: Float): Float {
        val y = if (yFromTop.isFinite()) yFromTop.coerceIn(0f, 1f) else 0.5f
        return (1f - y) * laneCount - 0.5f
    }

    /** Vertical centre (0 = top) of a lane band. */
    fun centerY(lane: Int): Float = 1f - (lane.coerceIn(0, laneCount - 1) + 0.5f) / laneCount

    /** Lane whose MIDI note is closest to [midi], preferring the lower on ties. */
    fun nearestLane(midi: Int): Int {
        var best = 0
        var bestDistance = Int.MAX_VALUE
        for (lane in 0 until laneCount) {
            val distance = kotlin.math.abs(notes[lane] - midi)
            if (distance < bestDistance) {
                best = lane
                bestDistance = distance
            }
        }
        return best
    }
}
