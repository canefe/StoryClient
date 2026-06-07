package com.canefe.storyclient.client.hediff

/**
 * Holds the local player's active hediffs for the right-edge HUD. Replaced
 * wholesale on each "story:hediffs" packet (full-state semantics — the server
 * only sends on change, and always sends the complete current list).
 */
object HediffHudState {
    @Volatile
    var active: List<HediffEntry> = emptyList()
        private set

    fun replaceAll(entries: List<HediffEntry>) {
        active = entries
    }

    fun clear() {
        active = emptyList()
    }
}

data class HediffEntry(
    val id: String,
    val severity: Float,
    val label: String,
    val stage: String,
    val description: String,
)
