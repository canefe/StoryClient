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

    @Test
    fun decodes_bodyPart_and_defaults_empty() {
        val withPart = """{"hediffs":[{"id":"cut","severity":0.3,"label":"Cut","stage":"Minor","description":"","bodyPart":"left_arm"}]}"""
        val withoutPart = """{"hediffs":[{"id":"malnutrition","severity":0.5,"label":"Malnutrition","stage":"Serious","description":"Weakened."}]}"""
        val a = HediffPacketReceiver.json.decodeFromString(HediffPacketReceiver.HediffsDTO.serializer(), withPart)
        val b = HediffPacketReceiver.json.decodeFromString(HediffPacketReceiver.HediffsDTO.serializer(), withoutPart)
        assertEquals("left_arm", a.hediffs[0].bodyPart)
        assertEquals("", b.hediffs[0].bodyPart)
    }
}
