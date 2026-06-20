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
}
