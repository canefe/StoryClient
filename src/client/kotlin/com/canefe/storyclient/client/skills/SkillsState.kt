package com.canefe.storyclient.client.skills

import kotlin.math.floor

/**
 * Holds the local player's active skills for the Skills window. Replaced
 * wholesale on each "story:skills" packet (full-state semantics — the server
 * only sends on change, and always sends the complete current list). Pure
 * state; no animation bookkeeping (unlike HediffHudState).
 */
object SkillsState {
    @Volatile
    var active: List<SkillEntry> = emptyList()
        private set

    @Synchronized
    fun replaceAll(entries: List<SkillEntry>) {
        active = entries
    }

    @Synchronized
    fun clear() {
        active = emptyList()
    }
}

data class SkillEntry(
    val id: String,
    val label: String,
    val category: String,
    val value: Float,
    val maxValue: Float,
    val xpFraction: Float,
) {
    /** Display milestone band: 0..20 over the 0..100 competence value. */
    val level: Int get() = floor(value / 5f).toInt()
}
