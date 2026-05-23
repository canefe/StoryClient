package com.canefe.storyclient.client.wheel

import java.util.concurrent.atomic.AtomicReference

/**
 * Last-received perception log, keyed by characterId. Updated by the
 * `story:perception_log` receiver. [PerceptionLogScreen] reads this snapshot
 * each render frame so an arriving payload immediately refreshes the UI.
 */
object PerceptionLogState {
    private val snapshot = AtomicReference<Snapshot?>(null)

    data class Snapshot(val characterId: String, val entries: List<PerceptionLogEntry>)

    fun set(characterId: String, entries: List<PerceptionLogEntry>) {
        snapshot.set(Snapshot(characterId, entries))
    }

    fun get(): Snapshot? = snapshot.get()

    fun forCharacter(characterId: String): List<PerceptionLogEntry>? {
        val snap = snapshot.get() ?: return null
        return if (snap.characterId == characterId) snap.entries else null
    }

    fun clear() {
        snapshot.set(null)
    }
}
