package com.canefe.storyclient.client.character

/**
 * The local player's own Story character data, pushed by StoryMC over
 * "story:char_self" (S2C). Full-state semantics: each packet replaces it.
 *
 * Read by client UI that needs to identify "my character" — e.g. the spawn
 * cinematic title. The vanilla MC player name is the account username, not the
 * Story character name, so this is the authoritative source for the latter.
 */
object SelfCharacterState {

    @Volatile
    var characterId: String = ""
        private set

    @Volatile
    var name: String = ""
        private set

    fun set(characterId: String, name: String) {
        this.characterId = characterId
        this.name = name
    }

    fun clear() {
        this.characterId = ""
        this.name = ""
    }
}
