package com.canefe.storyclient.client.health

import com.canefe.storyclient.client.hediff.HediffEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthStateTest {
    private fun h(id: String, part: String, sev: Float = 0.5f) =
        HediffEntry(id, sev, id, "Minor", "", part, 0f)

    @Test
    fun splits_whole_body_from_localized() {
        val list = listOf(h("malnutrition", ""), h("cut", "left_arm"))
        assertEquals(listOf("malnutrition"), HealthState.wholeBody(list).map { it.id })
        assertEquals(setOf("left_arm"), HealthState.byPart(list).keys)
        assertEquals(listOf("cut"), HealthState.byPart(list)["left_arm"]!!.map { it.id })
    }

    @Test
    fun partSeverity_is_max_fraction_on_part() {
        val list = listOf(h("cut", "left_arm", 0.2f), h("bruise", "left_arm", 0.7f))
        assertEquals(0.7f, HealthState.partSeverity(list, "left_arm"))
        assertEquals(0f, HealthState.partSeverity(list, "head"))
    }
}
