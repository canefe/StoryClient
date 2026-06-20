package com.canefe.storyclient.client.perception

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import java.util.UUID

class FovConeStoreTest {

    private fun cone(most: Long) =
        NpcFovConePayload.ConeParams(UUID(most, 0L), most.toInt(), 16f, 90f)

    @Test
    fun `replaceAll enabled populates, dedupes by uuid`() {
        FovConeStore.replaceAll(true, listOf(cone(1), cone(2)))
        assertEquals(2, FovConeStore.params().size)
        // a second set replaces (does not accumulate)
        FovConeStore.replaceAll(true, listOf(cone(3)))
        assertEquals(1, FovConeStore.params().size)
        assertFalse(FovConeStore.isEmpty())
    }

    @Test
    fun `replaceAll disabled clears`() {
        FovConeStore.replaceAll(true, listOf(cone(1)))
        FovConeStore.replaceAll(false, listOf(cone(1), cone(2)))
        assertTrue(FovConeStore.isEmpty())
        assertEquals(0, FovConeStore.params().size)
    }
}
