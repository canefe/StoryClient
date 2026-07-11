package com.canefe.storyclient.client.confrontation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfrontationStateTest {
    @Test
    fun `enter and exit toggle active`() {
        ConfrontationState.exit()
        assertFalse(ConfrontationState.active)
        ConfrontationState.enter("c1")
        assertTrue(ConfrontationState.active)
        assertEquals("c1", ConfrontationState.confrontationId)
        ConfrontationState.exit()
        assertFalse(ConfrontationState.active)
    }

    @Test
    fun `interaction lock reflects confrontation active`() {
        ConfrontationState.exit()
        assertFalse(com.canefe.storyclient.client.interaction.InteractionLock.locked)
        ConfrontationState.enter("c1")
        assertTrue(com.canefe.storyclient.client.interaction.InteractionLock.locked)
        ConfrontationState.exit()
    }

    @Test
    fun `choices s2c json decodes`() {
        val jsonStr = """{"id":"c1","target_character_id":"p1","prompt":"stop","allow_free_text":true,
          "choices":[{"id":"a","label":"Intimidate","check":{"kind":"static","dc":14}}]}"""
        val dto = ConfrontationState.json.decodeFromString(ChoicesS2C.serializer(), jsonStr)
        assertEquals(1, dto.choices.size)
        assertEquals("Intimidate", dto.choices[0].label)
        assertEquals(14, dto.choices[0].check?.dc)
    }
}
