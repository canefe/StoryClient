package com.canefe.storyclient.client.combat

import java.util.concurrent.ConcurrentHashMap

/**
 * Client-side cache of remote combatants' [CombatStatePushPayload] data.
 * Read by HUDs, the eventual pose-renderer mixin, and camera FX. Keyed by
 * Bukkit/server entity id (matches client-side Entity.getId()).
 */
object CombatStateClient {
    private val byEntityId = ConcurrentHashMap<Int, CombatStatePushPayload>()

    /**
     * Server-side entity id of the local player as seen by the combat system.
     * On some setups (Fabric integrated server, NPC plugins that rewrite ids)
     * `MinecraftClient.player.id` doesn't match the server bukkit id, so we
     * latch onto the first payload that carries stamina (only PlayerCombatants
     * report stamina) and treat that as "us".
     */
    @Volatile private var localServerEntityId: Int = -1

    fun update(payload: CombatStatePushPayload) {
        byEntityId[payload.entityId] = payload
        // Latch local id from the first payload that carries stamina.
        if (localServerEntityId == -1 && payload.staminaCurrent >= 0) {
            localServerEntityId = payload.entityId
        }
        if (payload.entityId == localServerEntityId) {
            DirectionInputCapture.localInWindup = (payload.stateOrdinal == 1)
            // 0=Idle, 1=Windup, 2=Active, 3=Recovery, 4=Blocking, 5=Staggered
            // Busy = anything that would reject a fresh SwingIntent.
            DirectionInputCapture.localBusy = payload.stateOrdinal in 1..3 || payload.stateOrdinal == 5
            CombatCameraEffects.onLocalStateChange(payload)
        }
    }

    /** Returns the latched local-player server entity id, or -1 if unknown. */
    fun localEntityId(): Int = localServerEntityId

    fun get(entityId: Int): CombatStatePushPayload? = byEntityId[entityId]

    /** Local player's last-known combat state, or null if we haven't latched yet. */
    fun getLocal(): CombatStatePushPayload? {
        val id = localServerEntityId
        if (id == -1) return null
        return byEntityId[id]
    }

    /** Stamina for the local player when the server pushed it; null if unknown. */
    fun localStamina(entityId: Int): Pair<Int, Int>? {
        // entityId arg is ignored; we use the latched local id.
        val payload = getLocal() ?: return null
        if (payload.staminaCurrent < 0) return null
        return payload.staminaCurrent to payload.staminaMax
    }

    fun clear() {
        byEntityId.clear()
    }
}
