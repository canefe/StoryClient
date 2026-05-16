package com.canefe.storyclient.client

import com.google.gson.Gson
import java.io.File

object StoryClientConfig {
    var modEnabled = true
    var messageVanishTime: Double = 5.0
    var dialogueScale: Double = 1.0
    var dialogueYOffset: Int = 80
    var useBubbleRenderer = false // Toggle between HUD and bubble rendering
    var use3DAudio = true // Toggle 3D positional audio
    var maxAudioDistance = 32.0 // Maximum distance to hear audio (blocks)
    var minAudioDistance = 2.0 // Distance before attenuation starts (blocks)

    // Directional combat
    var combatParryWindowScale: Double = 1.0
    var combatSlowMoEnabled: Boolean = true
    var combatScreenShakeIntensity: Float = 1.0f

    private val gson = Gson()
    private val configFile = File("config/storyclient.json")

    fun load() {
        if (configFile.exists()) {
            val json = configFile.readText()
            val loaded = gson.fromJson(json, StoryConfigData::class.java)
            modEnabled = loaded.modEnabled
            messageVanishTime = loaded.messageVanishTime
            dialogueScale = loaded.dialogueScale
            dialogueYOffset = loaded.dialogueYOffset
            useBubbleRenderer = loaded.useBubbleRenderer
            use3DAudio = loaded.use3DAudio
            maxAudioDistance = loaded.maxAudioDistance
            minAudioDistance = loaded.minAudioDistance
            combatParryWindowScale = loaded.combatParryWindowScale
            combatSlowMoEnabled = loaded.combatSlowMoEnabled
            combatScreenShakeIntensity = loaded.combatScreenShakeIntensity
        }
    }

    fun save() {
        val data = StoryConfigData(
            modEnabled, messageVanishTime, dialogueScale, dialogueYOffset,
            useBubbleRenderer, use3DAudio, maxAudioDistance, minAudioDistance,
            combatParryWindowScale, combatSlowMoEnabled, combatScreenShakeIntensity
        )
        configFile.parentFile.mkdirs()
        configFile.writeText(gson.toJson(data))
    }

    private data class StoryConfigData(
        val modEnabled: Boolean = true,
        val messageVanishTime: Double,
        val dialogueScale: Double = 1.0,
        val dialogueYOffset: Int = 80,
        val useBubbleRenderer: Boolean = false,
        val use3DAudio: Boolean = true,
        val maxAudioDistance: Double = 32.0,
        val minAudioDistance: Double = 2.0,
        val combatParryWindowScale: Double = 1.0,
        val combatSlowMoEnabled: Boolean = true,
        val combatScreenShakeIntensity: Float = 1.0f
    )
}