package com.canefe.storyclient.client.hediff

import kotlin.test.Test
import kotlin.test.assertEquals

class HediffPayloadTest {
    @Test
    fun decodes_payload_json() {
        val jsonStr = """{"hediffs":[{"id":"malnutrition","severity":0.5,"label":"Malnutrition","stage":"Serious","description":"Weakened."}]}"""
        val payload = HediffPacketReceiver.json.decodeFromString(
            HediffPacketReceiver.HediffsDTO.serializer(), jsonStr,
        )
        assertEquals(1, payload.hediffs.size)
        assertEquals("malnutrition", payload.hediffs[0].id)
        assertEquals("Serious", payload.hediffs[0].stage)
    }

    @Test
    fun decodes_empty() {
        val payload = HediffPacketReceiver.json.decodeFromString(
            HediffPacketReceiver.HediffsDTO.serializer(), """{"hediffs":[]}""",
        )
        assertEquals(0, payload.hediffs.size)
    }
}
