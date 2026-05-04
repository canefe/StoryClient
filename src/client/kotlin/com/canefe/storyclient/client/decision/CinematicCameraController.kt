package com.canefe.storyclient.client.decision

import net.minecraft.client.MinecraftClient
import net.minecraft.entity.Entity

object CinematicCameraController {

    private var originalCameraEntity: Entity? = null
    private var active = false
    private var currentPrompt: DecisionPrompt? = null

    private var lastHighlightIndex: Int = Int.MIN_VALUE

    fun start(prompt: DecisionPrompt) {
        val client = MinecraftClient.getInstance()
        originalCameraEntity = client.cameraEntity
        active = true
        currentPrompt = prompt
        lastHighlightIndex = Int.MIN_VALUE
    }

    fun stop() {
        val client = MinecraftClient.getInstance()
        originalCameraEntity?.let { client.setCameraEntity(it) }
        originalCameraEntity = null
        active = false
        currentPrompt = null
    }

    /** Called each client tick from ClientTickEvents. */
    fun tick() {
        if (!active) return
        val highlightIndex = DecisionState.highlightedVoiceIndex
        if (highlightIndex == lastHighlightIndex) return
        lastHighlightIndex = highlightIndex

        val client = MinecraftClient.getInstance()
        val world = client.world ?: return
        val player = client.player ?: return

        if (highlightIndex == -1) {
            // Top-down phase: fall back to player camera (free overhead camera not available without spectator)
            client.setCameraEntity(player)
        } else {
            val voice = currentPrompt?.npcVoices?.getOrNull(highlightIndex) ?: return
            val targetEntity = world.entities.firstOrNull { entity ->
                entity.name.string.equals(voice.name, ignoreCase = true) ||
                    entity.customName?.string?.equals(voice.name, ignoreCase = true) == true
            }
            if (targetEntity != null) {
                client.setCameraEntity(targetEntity)
            } else {
                client.setCameraEntity(player)
            }
        }
    }
}
