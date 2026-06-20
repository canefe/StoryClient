package com.canefe.storyclient.client.perception

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Current FOV cone params keyed by NPC uuid, set by the story:npc_fov packet. */
object FovConeStore {
    private val byUuid = ConcurrentHashMap<UUID, NpcFovConePayload.ConeParams>()

    fun replaceAll(enabled: Boolean, cones: List<NpcFovConePayload.ConeParams>) {
        byUuid.clear()
        if (enabled) {
            for (c in cones) byUuid[c.uuid] = c
        }
    }

    fun params(): Collection<NpcFovConePayload.ConeParams> = byUuid.values

    fun isEmpty(): Boolean = byUuid.isEmpty()
}
