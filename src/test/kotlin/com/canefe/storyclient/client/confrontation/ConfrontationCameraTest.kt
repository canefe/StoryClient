package com.canefe.storyclient.client.confrontation

import net.minecraft.util.math.Vec3d
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfrontationCameraTest {
    @Test
    fun `two-shot frames the midpoint of both participants`() {
        val a = Vec3d(0.0, 64.0, 0.0)
        val b = Vec3d(4.0, 64.0, 0.0)
        val mid = ConfrontationCameraController.midpoint(a, b)
        assertEquals(2.0, mid.x, 1e-6)
        assertEquals(64.0, mid.y, 1e-6)
        assertEquals(0.0, mid.z, 1e-6)
    }
}
