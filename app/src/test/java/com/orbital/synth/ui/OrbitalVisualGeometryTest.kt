package com.orbital.synth.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

class OrbitalVisualGeometryTest {
    @Test
    fun fixedDrawGeometryHasExpectedCountAndUnitDirections() {
        assertEquals(64, OrbitalVisualGeometry.dust.size)
        assertEquals(96, OrbitalVisualGeometry.ringTicks.size)
        assertEquals(33, OrbitalVisualGeometry.knobTicks.size)
        assertEquals(3, OrbitalVisualGeometry.junctions.size)

        val directions = OrbitalVisualGeometry.dust.map { it.direction } +
            OrbitalVisualGeometry.ringTicks.map { it.direction } +
            OrbitalVisualGeometry.knobTicks.map { it.direction } +
            OrbitalVisualGeometry.junctions

        directions.forEach { direction ->
            assertEquals(1.0, hypot(direction.x.toDouble(), direction.y.toDouble()), 1e-6)
        }
        assertEquals(12, OrbitalVisualGeometry.ringTicks.count { it.major })
        assertEquals(4, OrbitalVisualGeometry.ringTicks.count { it.accent })
        assertEquals(9, OrbitalVisualGeometry.knobTicks.count { it.major })
    }

    @Test
    fun memoizedRadiiMatchUncachedTwelveLayerHarmonics() {
        for (morph in listOf(0f, .42f, 1f)) {
            for (movement in listOf(0f, .37f, 1f)) {
                val cached = OrbitalVisualGeometry.ribbonRadii(morph, movement)
                assertEquals(12, cached.size)
                val frequency = 2.75 + morph.toDouble() * 2.8
                cached.forEachIndexed { layer, radii ->
                    assertEquals(OrbitalTraceMath.samples.size, radii.size)
                    val phase = movement.toDouble() * PI * 2.0 + layer * .33
                    OrbitalTraceMath.samples.forEachIndexed { sampleIndex, sample ->
                        val twist = sin(sample.radians * frequency + phase) * .13 +
                            sin(sample.radians * 7.0 - phase * .65) * .055
                        val expected = .37 + layer * .048 + twist * .86
                        assertEquals(expected, radii[sampleIndex], 1e-12)
                    }
                }
            }
        }
    }
}
