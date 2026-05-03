package com.canefe.storyclient.client.recognition

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-perceiver recognition state, pushed by the server on `story:recognition_set`.
 * Maps `characterId → realName` for everyone the local player currently knows.
 *
 * The HelixNametagRenderer pulls from here to decide whether to show the real
 * name (top line) + descriptor (bottom line) or only the descriptor.
 */
object RecognitionCache {
    private val knownByCharacterId = ConcurrentHashMap<String, String>()

    fun replaceAll(known: Map<String, String>) {
        knownByCharacterId.clear()
        knownByCharacterId.putAll(known)
    }

    fun realNameOf(characterId: String): String? = knownByCharacterId[characterId]

    fun knows(characterId: String): Boolean = knownByCharacterId.containsKey(characterId)

    fun size(): Int = knownByCharacterId.size
}
