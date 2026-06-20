package com.canefe.storyclient.client.perception

import kotlin.test.Test
import kotlin.test.assertEquals
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID

class NpcFovConePayloadTest {

    /** Build bytes exactly as the server's NpcFovBroadcaster.encode does. */
    private fun serverBytes(enabled: Boolean, cones: List<NpcFovConePayload.ConeParams>): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeByte(if (enabled) 1 else 0)
            out.writeShort(cones.size)
            for (c in cones) {
                out.writeLong(c.uuid.mostSignificantBits)
                out.writeLong(c.uuid.leastSignificantBits)
                out.writeInt(c.entityId)
                out.writeFloat(c.sightRange)
                out.writeFloat(c.fovHalfDeg)
            }
        }
        return baos.toByteArray()
    }

    @Test
    fun `decode round-trips the server wire format`() {
        val cones = listOf(
            NpcFovConePayload.ConeParams(UUID(1L, 2L), 100, 16.0f, 90.0f),
            NpcFovConePayload.ConeParams(UUID(3L, 4L), -1, 8.5f, 45.0f),
        )
        val decoded = NpcFovConePayload.decodeBytes(serverBytes(true, cones))
        assertEquals(true, decoded.enabled)
        assertEquals(cones, decoded.cones)
    }

    @Test
    fun `decode clear yields empty disabled set`() {
        val decoded = NpcFovConePayload.decodeBytes(serverBytes(false, emptyList()))
        assertEquals(false, decoded.enabled)
        assertEquals(emptyList<NpcFovConePayload.ConeParams>(), decoded.cones)
    }
}
