package com.canefe.storyclient.client.puppet

/**
 * Client-side mirror of the player's server-side puppet group. Updated by
 * [PuppetGroupPayload] handler. Read by HUD overlay and the right-click
 * intercept mixin to know whether to swallow the click.
 *
 * Group membership is keyed by characterId so it stays stable regardless of
 * what label/name the local DM currently sees for each NPC.
 */
object PuppetState {
    @Volatile var groupCharacterIds: List<String> = emptyList()
        private set

    val inPuppetMode: Boolean get() = groupCharacterIds.isNotEmpty()

    fun replaceAll(characterIds: List<String>) {
        groupCharacterIds = characterIds
    }

    /** Optimistic local update before server pushes back the new group state. */
    fun localToggle(characterId: String) {
        groupCharacterIds =
            if (groupCharacterIds.contains(characterId)) {
                groupCharacterIds - characterId
            } else {
                groupCharacterIds + characterId
            }
    }

    fun localClear() {
        groupCharacterIds = emptyList()
    }
}
