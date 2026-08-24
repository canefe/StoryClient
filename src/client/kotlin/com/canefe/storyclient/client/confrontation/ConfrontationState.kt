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
        choices = view.choices.map { dto ->
            val check = dto.check?.let { c ->
                CheckView(
                    kind = c.kind,
                    skill = c.skill,
                    actorSkillValue = c.actor_skill_value.toInt(),
                    dc = c.dc,
                    vsSkill = c.vs_skill,
                    targetSkillValue = c.target_skill_value?.toInt(),
                )
            }
            ChoiceView(dto.id, dto.label, check)
        }
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

    /**
     * Full teardown for connect/disconnect: clears state AND stops the camera
     * controller (which restores the hidden HUD). Use this on JOIN/DISCONNECT so a
     * stale confrontation can never strand the player in a lock/hidden-HUD/camera
     * state — no exit packet is guaranteed on an ungraceful disconnect.
     */
    fun forceReset() {
        val wasActive = active
        exit()
        // Always stop the camera (idempotent) so hudHidden is restored even if the
        // camera thought it was inactive.
        ConfrontationCameraController.stop()
        if (wasActive) {
            // Belt-and-suspenders: ensure the vanilla HUD is back on.
            net.minecraft.client.MinecraftClient.getInstance().options.hudHidden = false
        }
        ConfrontationOverlay.cancelFreeform()
    }
}

/**
 * A single option shown to the player.
 *
 * [check] is null when the option rolls nothing (unchecked). When present it
 * carries the full mechanics the player is betting on — skill name, the player's
 * own value in that skill, the DC (static), and the opposed defender's skill +
 * value — so the UI can render "which skill, how good am I, what am I beating".
 */
data class ChoiceView(val id: String, val label: String, val check: CheckView?)

/** Resolved mechanics for a checked choice, ready for display. */
data class CheckView(
    val kind: String,              // "static" | "opposed"
    val skill: String,             // acting player's skill being rolled
    val actorSkillValue: Int,      // the player's value in [skill] (0 if unknown)
    val dc: Int?,                  // static only
    val vsSkill: String?,          // opposed only — defender's skill
    val targetSkillValue: Int?,    // opposed only — defender's value
)

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
    val skill: String = "",
    val dc: Int? = null,
    val vs_skill: String? = null,
    val actor_skill_value: Double = 0.0,
    val target_skill_value: Double? = null,
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
