package com.canefe.storyclient.client.skills

import kotlin.test.Test
import kotlin.test.assertEquals

class SkillsStateTest {
    @Test
    fun level_is_the_competence_value() {
        assertEquals(4, SkillEntry("a", "A", "c", 4f, 100f, 0f).level)
        assertEquals(5, SkillEntry("a", "A", "c", 5f, 100f, 0f).level)
        assertEquals(52, SkillEntry("a", "A", "c", 52f, 100f, 0f).level)
    }

    @Test
    fun replace_all_then_clear() {
        SkillsState.replaceAll(listOf(SkillEntry("a", "A", "c", 5f, 100f, 0f)))
        assertEquals(1, SkillsState.active.size)
        SkillsState.clear()
        assertEquals(0, SkillsState.active.size)
    }
}
