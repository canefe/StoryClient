package com.canefe.storyclient.client.confrontation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Client-side state for a LOCKED confrontation scene. Pure — no MC packet or
 * render code — so it stays unit-testable. Fed by [ConfrontationPacketReceiver]
 * and read by the overlay / camera controller.
 */
object ConfrontationState {

    val json = Json { ignoreUnknownKeys = true }

    @Volatile
    var active: Boolean = false
        private set

    var confrontationId: String? = null
        private set

    /** Prompt for the local player's current turn (null when not our turn). */
    var prompt: String? = null

    /** Choices offered to the local player this turn. */
    var choices: List<ChoiceView> = emptyList()

    /** Whether free-text ("say anything") is allowed this turn. */
    var allowFreeText: Boolean = false

    /** True when it is the local player's turn. */
    var myTurn: Boolean = false

    /** Character id whose turn / who is speaking — the close-up camera target. */
    var activeCharacterId: String? = null

    /** The active character's target (for the two-shot). May be null. */
    var targetCharacterId: String? = null

    fun enter(id: String) {
        confrontationId = id
        active = true
    }

    fun setChoices(view: ChoicesS2C) {
        prompt = view.prompt
        allowFreeText = view.allow_free_text
        choices = view.choices.map { ChoiceView(it.id, it.label, it.check?.dc) }
        myTurn = true
    }

    fun setTurn(active: String?) {
        activeCharacterId = active
        // A choices packet flips myTurn true; a turn packet naming someone else
        // clears our turn. The receiver sets this from the local character id.
    }

    fun clearMyTurn() {
        myTurn = false
        choices = emptyList()
        prompt = null
    }

    fun exit() {
        active = false
        confrontationId = null
        prompt = null
        choices = emptyList()
        allowFreeText = false
        myTurn = false
        activeCharacterId = null
        targetCharacterId = null
    }
}

/** A single option shown to the player. [dc] is null when the choice is unchecked. */
data class ChoiceView(val id: String, val label: String, val dc: Int?)

// ── S2C wire DTOs (snake_case to match story-go BridgeMessage.Data) ──────────

@Serializable
data class ChoicesS2C(
    val id: String,
    val target_character_id: String,
    val prompt: String,
    val allow_free_text: Boolean = false,
    val choices: List<ChoiceDto> = emptyList(),
)

@Serializable
data class ChoiceDto(
    val id: String,
    val label: String,
    val check: CheckDto? = null,
)

@Serializable
data class CheckDto(
    val kind: String,
    val dc: Int? = null,
)

@Serializable
data class EnterS2C(
    val id: String,
    val roster: List<String> = emptyList(),
    val initiative: List<String> = emptyList(),
    val framing: String = "",
)

@Serializable
data class TurnS2C(
    val id: String,
    val active_character_id: String? = null,
)

@Serializable
data class ExitS2C(
    val id: String,
    val reason: String = "",
)
