package com.canefe.storyclient.client.skills

import kotlin.test.Test
import kotlin.test.assertEquals

class SkillsStateTest {
    @Test
    fun level_is_value_div_five_floored() {
        assertEquals(0, SkillEntry("a", "A", "c", 4f, 100f, 0f).level)
        assertEquals(1, SkillEntry("a", "A", "c", 5f, 100f, 0f).level)
        assertEquals(10, SkillEntry("a", "A", "c", 52f, 100f, 0f).level)
    }

    @Test
    fun replace_all_then_clear() {
        SkillsState.replaceAll(listOf(SkillEntry("a", "A", "c", 5f, 100f, 0f)))
        assertEquals(1, SkillsState.active.size)
        SkillsState.clear()
        assertEquals(0, SkillsState.active.size)
    }
}
