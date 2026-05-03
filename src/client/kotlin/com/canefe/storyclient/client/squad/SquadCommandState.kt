package com.canefe.storyclient.client.squad

/**
 * Client state for the Bannerlord-style squad command system.
 *
 * - [commandMode]: true when the player has the command HUD open and is
 *   issuing orders. Toggled by Task 9's keybind handler.
 * - [selectedSquadIds]: which squads receive the next order. Multi-select.
 *   Persists across command-mode toggles so the player doesn't re-pick.
 */
object SquadCommandState {
    @Volatile var commandMode: Boolean = false
        private set

    /** Stable squad ids currently selected. Order doesn't matter. */
    @Volatile var selectedSquadIds: Set<String> = emptySet()
        private set

    fun enterCommandMode() {
        commandMode = true
        // Drop any selection that no longer exists in the cache (e.g. squad deleted).
        val live = SquadListCache.entries.map { it.id }.toSet()
        selectedSquadIds = selectedSquadIds.intersect(live)
    }

    fun exitCommandMode() {
        commandMode = false
    }

    fun toggleCommandMode() {
        if (commandMode) exitCommandMode() else enterCommandMode()
    }

    /** Add or remove a squad from the selection. */
    fun toggleSelection(squadId: String) {
        selectedSquadIds =
            if (selectedSquadIds.contains(squadId)) selectedSquadIds - squadId
            else selectedSquadIds + squadId
    }

    /** Select all commandable squads. */
    fun selectAll() {
        selectedSquadIds = SquadListCache.entries.map { it.id }.toSet()
    }

    /** Clear selection. */
    fun clearSelection() {
        selectedSquadIds = emptySet()
    }

    /**
     * Resolve hotkey number to the squad id at that ordinal position, if any.
     * 1-based to match what's drawn in the HUD.
     */
    fun squadAtOrdinal(ordinal: Int): SquadListCache.Entry? {
        val list = SquadListCache.entries
        if (ordinal < 1 || ordinal > list.size) return null
        return list[ordinal - 1]
    }
}
