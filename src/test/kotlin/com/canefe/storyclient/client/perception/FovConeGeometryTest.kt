package com.canefe.storyclient.client.perception

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

class FovConeGeometryTest {

    @Test
    fun `arc spans full fov with steps+1 points`() {
        // yaw=0 faces +Z. fovHalf=90 => sweep -90..+90 (a half disc).
        val pts = FovConeRenderer.arcPoints(yawRad = 0.0, fovHalfDeg = 90f, sightRange = 10f, steps = 4)
        assertEquals(5, pts.size) // steps + 1 boundary samples

        // All endpoints lie on the radius circle: magnitude == sightRange.
        for ((x, z) in pts) {
            val mag = Math.sqrt((x.toDouble() * x + z.toDouble() * z))
            assert(abs(mag - 10.0) < 1e-3) { "point ($x,$z) not on radius: mag=$mag" }
        }

        // Middle sample (index 2) points straight forward = (0, +sightRange) at yaw 0.
        val (mx, mz) = pts[2]
        assert(abs(mx.toDouble()) < 1e-3) { "mid x should be ~0, was $mx" }
        assert(abs(mz.toDouble() - 10.0) < 1e-3) { "mid z should be ~10, was $mz" }
    }

    @Test
    fun `facingVector matches MC yaw-pitch convention`() {
        // yaw 0, pitch 0 → straight ahead = +Z.
        val fwd = FovConeRenderer.facingVector(0.0, 0.0)
        assert(abs(fwd.x) < 1e-6 && abs(fwd.y) < 1e-6 && abs(fwd.z - 1f) < 1e-6) { "fwd was $fwd" }

        // pitch +90° (looking straight down) → -Y.
        val down = FovConeRenderer.facingVector(0.0, Math.toRadians(90.0))
        assert(abs(down.y + 1f) < 1e-5) { "looking down should be -Y, was $down" }
    }

    @Test
    fun `cone ring points lie on sphere at fovHalfDeg from axis`() {
        val axis = FovConeRenderer.facingVector(0.0, 0.0) // +Z
        val half = 30f
        val r = 12f
        val ring = FovConeRenderer.conePoints(axis, fovHalfDeg = half, sightRange = r, steps = 16)
        assertEquals(17, ring.size) // steps + 1 (closed ring)

        val cosHalf = Math.cos(Math.toRadians(half.toDouble()))
        for (p in ring) {
            val mag = Math.sqrt((p.x.toDouble() * p.x + p.y.toDouble() * p.y + p.z.toDouble() * p.z))
            assert(abs(mag - r.toDouble()) < 1e-3) { "ring point $p off radius: mag=$mag" }
            // angle from axis (+Z) == fovHalfDeg → dot(axis, normalized) == cos(half).
            val dot = p.z.toDouble() / mag
            assert(abs(dot - cosHalf) < 1e-3) { "ring point $p not at $half° from axis: dot=$dot" }
        }
    }
}
