package com.canefe.storyclient.client.pacing

import com.canefe.storyclient.client.TypingManager
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
        val now = System.currentTimeMillis()
        val bundle = bundleFor(npcKey = npcId, npcUuid = npcId, now = now)
        // First dialogue chunk of this bundle is "new"; subsequent chunks
        // (e.g. streaming tokens) are updates to the same session.
        val isNew = bundle.dialogue.isEmpty() && !TypingManager.isSessionActive(npcId)
        bundle.dialogue.add(DialogueChunk(text, color, isNew))
        if (voicePending) {
            bundle.voicePending = true
        }
        extendSeal(bundle, now)
    }

    /**
     * Get the open bundle for [npcKey], opening one if needed. Caller must
     * call [extendSeal] after mutating the bundle's contents to update its
     * deadline.
     */
    private fun bundleFor(npcKey: String, npcUuid: String?, now: Long): Bundle {
        return openBundles.getOrPut(npcKey) {
            Bundle(
                npcKey = npcKey,
                npcUuid = npcUuid,
                openedAtMs = now,
                sealAtMs = now + BUNDLE_WINDOW_MS,
            )
        }
    }

    /**
     * Extend the bundle's seal deadline based on what's inside:
     *   - normal: 200ms after the most recent event, bounded by 1000ms after
     *     the bundle was opened (prevents an event-spamming NPC from holding
     *     the bundle open forever)
     *   - voice-pending without audio yet: up to 3000ms after the bundle was
     *     opened (preserves the legacy VOICE_WAIT_TIMEOUT_MS semantic)
     */
    private fun extendSeal(bundle: Bundle, now: Long) {
        val cap = if (bundle.voicePending && bundle.voiceAudio == null) {
            bundle.openedAtMs + VOICE_WAIT_MS
        } else {
            bundle.openedAtMs + BUNDLE_MAX_OPEN_MS
        }
        bundle.sealAtMs = minOf(now + BUNDLE_WINDOW_MS, cap)
    }

    fun onVoiceAudio(npcId: String, audioBytes: ByteArray) {
        val now = System.currentTimeMillis()
        val bundle = bundleFor(npcKey = npcId, npcUuid = npcId, now = now)
        bundle.voiceAudio = audioBytes
        // Audio arrival cancels the voice-wait extension — once we have the
        // bytes there's no reason to keep the bundle open beyond the normal
        // 200ms window (capped at openedAt + 1000ms).
        extendSeal(bundle, now)
    }

    fun onEmote(entityId: Int, emoteId: String) {
        // Implemented in Task 5.
    }

    fun onActionLabel(npcId: String, label: String) {
        // Implemented in Task 6.
    }

    fun tick() {
        tickAt(System.currentTimeMillis())
    }

    /** Test-only: drive [tick] with an injected clock. */
    internal fun tickForTest(nowMs: Long) {
        tickAt(nowMs)
    }

    private fun tickAt(nowMs: Long) {
        // 1. Seal any open bundles whose sealAtMs has passed.
        val toSeal = openBundles.entries.toList()
            .filter { (_, bundle) -> nowMs >= bundle.sealAtMs }
            .map { it.key }
        for (key in toSeal) {
            val sealed = openBundles.remove(key) ?: continue
            // Dedupe emotes at seal time (e.g. LAUGH+LAUGH+LAUGH → LAUGH).
            val deduped = sealed.emotes.distinct()
            sealed.emotes.clear()
            sealed.emotes.addAll(deduped)
            readyQueue.addLast(sealed)
        }

        // 2. Pop at most one bundle per POP_INTERVAL_MS.
        if (nowMs - lastPopMs >= POP_INTERVAL_MS) {
            val bundle = readyQueue.pollFirst()
            if (bundle != null) {
                lastPopMs = nowMs
                replay(bundle)
            }
        }
    }

    private fun replay(bundle: Bundle) {
        // Implemented in Task 7. For now, no-op so Task 2 lands without
        // depending on the renderer wiring.
    }

    /** Test-only: reset all internal state. */
    internal fun resetForTest() {
        openBundles.clear()
        readyQueue.clear()
        lastPopMs = 0L
    }
}
