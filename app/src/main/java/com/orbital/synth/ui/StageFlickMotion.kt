package com.orbital.synth.ui

import kotlin.math.abs
import kotlin.math.hypot

/**
 * VISUAL ONLY. Velocity is derived from real normalized per-pointer position
 * and Android event-time deltas. It is never passed to MIDI note velocity,
 * hardware force/pressure, FX, or audio telemetry and cannot invent PCM.
 *
 * One pointer recognizer owns this fixed-size mutable map on the UI thread.
 * A new Sample allocates at DOWN; MOVE mutates the existing Sample without
 * adding new collections/arrays on the high-rate drag path.
 */
internal class StageFlickVelocityTracker(private val maxPointers: Int = 10) {
    init { require(maxPointers in 1..64) }

    private class Sample(var x: Float, var y: Float, var timeMs: Long)
    private val contacts = HashMap<Long, Sample>(maxPointers)

    val pointerCount: Int get() = contacts.size

    fun down(pointerId: Long, x: Float, y: Float, atMs: Long): Boolean {
        if (pointerId !in contacts && contacts.size >= maxPointers) return false
        contacts[pointerId] = Sample(unit(x, .5f), unit(y, .5f), atMs)
        return true
    }

    /**
     * null = no fresh POSITION sample (or unknown/stale pointer);
     * 0 = a real move whose timestamp is unusable, not a fabricated speed.
     * The speed scale intentionally agrees with TouchPerformanceMapper's
     * existing perceptual 1.7 screen-widths/second denominator.
     */
    fun move(pointerId: Long, x: Float, y: Float, atMs: Long): Float? {
        val prior = contacts[pointerId] ?: return null
        val px = unit(x, prior.x)
        val py = unit(y, prior.y)
        val dx = px - prior.x
        val dy = py - prior.y
        if (abs(dx) + abs(dy) < .0001f) return null
        val elapsed = atMs - prior.timeMs
        prior.x = px
        prior.y = py
        prior.timeMs = atMs
        if (elapsed !in 1L..1_000L) return 0f
        return (hypot(dx, dy) * 1_000f / elapsed / 1.7f).coerceIn(0f, 1f)
    }

    fun up(pointerId: Long) {
        contacts.remove(pointerId)
    }

    fun cancelAll() {
        contacts.clear()
    }

    private fun unit(value: Float, fallback: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 1f) else fallback
}

/**
 * Shared deterministic easing curve for the one-shot 420ms Compose animation.
 * At quiet idle, no AnimationClock is mounted: after the finite decay it stops.
 */
internal object StageFlickMotionMath {
    const val DECAY_MS = 420

    fun smoothProgress(fraction: Float): Float {
        val t = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        return t * t * (3f - 2f * t)
    }

    fun energyAt(velocity: Float, elapsedMs: Long): Float {
        val initial = if (velocity.isFinite()) velocity.coerceIn(0f, 1f) else 0f
        val fraction = elapsedMs.coerceAtLeast(0L).toFloat() / DECAY_MS
        return initial * (1f - smoothProgress(fraction))
    }
}
