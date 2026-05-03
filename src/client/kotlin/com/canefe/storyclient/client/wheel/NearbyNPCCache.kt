package com.canefe.storyclient.client.wheel

import net.minecraft.client.MinecraftClient
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.util.math.Box
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches the per-player "nearby NPCs" bundle pushed by the server on the
 * `story:nearby_npcs` channel. The wheel HUD reads from here to figure out
 * the looked-at NPC and which actions to show.
 */
object NearbyNPCCache {
    /** Wire entity types — must match server-side `NearbyNPCBroadcaster.TYPE_*`. */
    const val TYPE_NPC: Byte = 0
    const val TYPE_PLAYER: Byte = 1

    data class Entry(
        val uuid: UUID,
        /** Per-perceiver display label — real name when known, else descriptor. */
        val name: String,
        val characterId: String,
        /** Always-the-fallback descriptor (full appearance prose); used as the second nametag line when known. */
        val descriptor: String,
        /** Compact "Black-haired Elf"-style label for chat/wheel/bubble use. */
        val shortLabel: String = "",
        /**
         * Server-known real name. Sent only when the perceiver is a DM (gated by
         * the same permission as [canSpeakAs]); empty string for non-DMs. DM-only
         * UI (puppet wheel, follow-char target list) should prefer this over
         * [name] so the DM sees who they're targeting.
         */
        val realName: String,
        /** DM-only: rounded current HP. -1 if perceiver lacks DM permission. */
        val hp: Int,
        /** DM-only: rounded max HP. -1 if perceiver lacks DM permission. */
        val maxHp: Int,
        val canSpeakAs: Boolean,
        val isFollowing: Boolean,
        val entityType: Byte = TYPE_NPC,
    ) {
        /** Best label to show a DM in DM-only UI. Falls back to [name] for safety. */
        val dmLabel: String get() = realName.ifEmpty { name }
        val isDmView: Boolean get() = realName.isNotBlank()
    }

    private val byUuid = ConcurrentHashMap<UUID, Entry>()

    fun replaceAll(entries: List<Entry>) {
        byUuid.clear()
        for (e in entries) byUuid[e.uuid] = e
    }

    fun get(uuid: UUID): Entry? = byUuid[uuid]

    fun all(): Collection<Entry> = byUuid.values

    /**
     * Raycasts from the player's camera up to [maxDistance] blocks and returns
     * the matching cached NPC info, or null if no looked-at entity is in cache.
     */
    fun lookedAt(maxDistance: Double = 150.0): Entry? {
        val hit = lookedAtEntity(maxDistance) ?: return null
        return byUuid[hit.uuid]?.takeIf { it.entityType == TYPE_NPC }
    }

    /**
     * Like [lookedAt] but doesn't filter on entity type — used by the nametag
     * renderer, which renders for both NPCs and players.
     */
    fun lookedAtAny(maxDistance: Double = 150.0): Entry? {
        val hit = lookedAtEntity(maxDistance) ?: return null
        return byUuid[hit.uuid]
    }

    private fun lookedAtEntity(maxDistance: Double): Entity? {
        val client = MinecraftClient.getInstance()
        val camera = client.cameraEntity ?: return null
        val world = camera.world
        val start = camera.eyePos
        val end = start.add(camera.getRotationVec(1.0f).multiply(maxDistance))
        val box = Box(start, end).expand(1.0)

        return world.getOtherEntities(camera, box) {
            it is LivingEntity && byUuid.containsKey(it.uuid)
        }.minByOrNull { it.squaredDistanceTo(camera) }
    }
}
