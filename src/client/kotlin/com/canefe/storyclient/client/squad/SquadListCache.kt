package com.canefe.storyclient.client.squad

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Client mirror of the server's commandable-squads list for this player.
 *
 * Updated by [SquadListPayload] handler. Read by [SquadListHud] for the
 * sidebar and by [SquadCommandState] for selection lookups.
 */
object SquadListCache {
    data class Entry(
        val id: String,
        val name: String,
        val color: Int, // 0xRRGGBB (no alpha)
        val memberCount: Int,
        val orderLabel: String,
        val formationLabel: String,
        val memberUuids: List<UUID>,
    )

    @Volatile var entries: List<Entry> = emptyList()
        private set

    /** Reverse index: any NPC member uuid -> the squad it belongs to (the FIRST squad if multi). */
    private val byMemberUuid = ConcurrentHashMap<UUID, Entry>()

    fun replaceAll(newEntries: List<Entry>) {
        entries = newEntries
        byMemberUuid.clear()
        for (e in newEntries) {
            for (m in e.memberUuids) {
                byMemberUuid.putIfAbsent(m, e)
            }
        }
    }

    fun byId(id: String): Entry? = entries.firstOrNull { it.id == id }

    fun squadOf(npcUuid: UUID): Entry? = byMemberUuid[npcUuid]
}
