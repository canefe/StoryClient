package com.canefe.storyclient.client.emote

import net.minecraft.util.Identifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Renders floating emote icons (laugh, cry, anger, pain, shock) above NPCs.
 *
 * Lifecycle per emote: RISE (350ms) → HOLD (900ms) → EXIT (400ms) — matches
 * the perception popup curve so the visual rhythm feels consistent. One emote
 * at a time per entity; a new emote replaces the previous one. Unknown emote
 * ids are silently dropped (forward-compat: sim can ship new ids before the
 * client knows them).
 *
 * Unlike [com.canefe.storyclient.client.perception.PerceptionPopupRenderer],
 * emotes are always visible — they do not gate on crosshair target. The point
 * of an emote is that players notice it from across the room.
 *
 * This file contains scheduling/data only. The GL draw call lives in a sibling
 * `render(WorldRenderContext)` method added in a follow-up commit.
 */
object EmoteRenderer {

    const val RISE_MS = 350L
    const val HOLD_MS = 900L
    const val EXIT_MS = 400L
    const val TOTAL_MS = RISE_MS + HOLD_MS + EXIT_MS

    data class Active(
        val emoteId: String,
        val texture: Identifier,
        val startMs: Long,
    )

    private val active = ConcurrentHashMap<Int, Active>()

    private val allowlist: Map<String, Identifier> = mapOf(
        "cry"   to Identifier.of("storyclient", "textures/emote/cry.png"),
        "anger" to Identifier.of("storyclient", "textures/emote/anger.png"),
        "pain"  to Identifier.of("storyclient", "textures/emote/pain.png"),
        "laugh" to Identifier.of("storyclient", "textures/emote/laugh.png"),
        "shock" to Identifier.of("storyclient", "textures/emote/shock.png"),
    )

    /** Server→client entry point. Called from the payload handler. */
    fun onEmote(entityId: Int, emoteId: String, nowMs: Long = System.currentTimeMillis()) {
        val tex = allowlist[emoteId] ?: return // unknown id — drop silently
        active[entityId] = Active(emoteId, tex, nowMs)
    }

    /** Drops emotes whose lifetime is exhausted. Call once per frame. */
    fun sweep(nowMs: Long = System.currentTimeMillis()) {
        val cutoff = nowMs - TOTAL_MS
        active.entries.removeIf { it.value.startMs < cutoff }
    }

    /** Test-only accessor. */
    internal fun activeMapForTest(): Map<Int, Active> = active
}
