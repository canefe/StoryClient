package com.canefe.storyclient.client.pacing

import com.canefe.storyclient.client.perception.PopupType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Single global pacing queue for all NPC-driven visual events on the client.
 *
 * Events for the same NPC arriving within a 200ms window are merged into a
 * [Bundle] that pops atomically — preserving dialogue+voice sync and keeping
 * one NPC's reaction visually coherent. Bundles drip out of [readyQueue] at
 * one per second regardless of how many NPCs are talking at once. The
 * 3-second voice-wait semantic from the old `TypingManager.pendingVoiceDialogues`
 * is preserved as a bundle-level override on [Bundle.sealAtMs].
 *
 * Bundle key: the NPC's uuid-string when available, falling back to
 * `"entity:<entityId>"` for emote-only events whose payload carries no uuid.
 * Emote-only bundles never need to merge with dialogue (dialogue always has
 * a uuid), so the two key spaces don't collide.
 *
 * See `docs/superpowers/specs/2026-05-27-npc-event-pacer-design.md`.
 */
object NpcEventPacer {

    internal const val BUNDLE_WINDOW_MS = 200L
    internal const val BUNDLE_MAX_OPEN_MS = 1000L
    internal const val VOICE_WAIT_MS = 3000L
    internal const val POP_INTERVAL_MS = 1000L
    internal const val QUEUE_DEPTH_COLLAPSE_THRESHOLD = 8

    internal data class DialogueChunk(
        val text: String,
        val color: String?,
        val isNew: Boolean,
    )

    internal data class Bundle(
        val npcKey: String,
        val npcUuid: String?,          // null for emote-only entity-keyed bundles
        val openedAtMs: Long,
        var sealAtMs: Long,
        val dialogue: MutableList<DialogueChunk> = mutableListOf(),
        var voiceAudio: ByteArray? = null,
        var voicePending: Boolean = false,
        val emotes: MutableList<String> = mutableListOf(),
        var actionLabel: String? = null,
        var emoteEntityId: Int = -1,
    )

    internal val openBundles = ConcurrentHashMap<String, Bundle>()
    internal val readyQueue = ConcurrentLinkedDeque<Bundle>()
    internal var lastPopMs = 0L

    // ── Public API (no-op until Tasks 2+ implement the methods) ────────────

    fun onDialogueChunk(npcId: String, text: String, color: String?, voicePending: Boolean) {
        // Implemented in Task 3.
    }

    fun onVoiceAudio(npcId: String, audioBytes: ByteArray) {
        // Implemented in Task 4.
    }

    fun onEmote(entityId: Int, emoteId: String) {
        // Implemented in Task 5.
    }

    fun onActionLabel(npcId: String, label: String) {
        // Implemented in Task 6.
    }

    fun tick() {
        // Implemented in Task 2.
    }

    /** Test-only: drive [tick] with an injected clock. */
    internal fun tickForTest(nowMs: Long) {
        // Implemented in Task 2.
    }

    /** Test-only: reset all internal state. */
    internal fun resetForTest() {
        openBundles.clear()
        readyQueue.clear()
        lastPopMs = 0L
    }
}
