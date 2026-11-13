package org.example.test.world

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

/** The manual-placement transform used when a resumed scan cannot relocalize. */
class PlacementTest {
    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    @Test
    fun yawZeroFacesPlusZ() {
        val s = placementSegment(identity, 2.0, 1.5, 3.0, 0.0)
        val fwd = s.dirToWorld(doubleArrayOf(0.0, 0.0, 1.0))
        assertEquals(0.0, fwd[0], 1e-9); assertEquals(0.0, fwd[1], 1e-9); assertEquals(1.0, fwd[2], 1e-9)
    }

    @Test
    fun yawNinetyFacesPlusX() {
        val s = placementSegment(identity, 0.0, 0.0, 0.0, PI / 2)
        val fwd = s.dirToWorld(doubleArrayOf(0.0, 0.0, 1.0))
        assertEquals(1.0, fwd[0], 1e-9); assertEquals(0.0, fwd[2], 1e-9)
    }

    @Test
    fun startPointLandsOnTheTappedPosition() {
        val s = placementSegment(identity, 2.0, 1.5, 3.0, 1.0)
        val p = s.toWorld(doubleArrayOf(0.0, 0.0, 0.0))
        assertEquals(2.0, p[0], 1e-9); assertEquals(1.5, p[1], 1e-9); assertEquals(3.0, p[2], 1e-9)
    }

    @Test
    fun directionsIgnoreTheOffsetAndKeepLength() {
        val s = placementSegment(identity, 10.0, 10.0, 10.0, 0.7)
        val d = s.dirToWorld(doubleArrayOf(0.3, -0.2, 0.9))
        assertEquals(0.3 * 0.3 + 0.2 * 0.2 + 0.9 * 0.9, d[0] * d[0] + d[1] * d[1] + d[2] * d[2], 1e-9)
        assertEquals(-0.2, d[1], 1e-9) // yaw is about the vertical axis
    }
}
