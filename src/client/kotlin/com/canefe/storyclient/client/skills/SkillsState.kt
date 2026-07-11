package com.canefe.storyclient.client.skills

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
    /** The competence value is the level; xpFraction fills toward the next one. */
    val level: Int get() = value.toInt()
}
