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

    // DM display preference: when true, DMs see real names alongside
    // recognition labels on nametags. Server always sends real names to DMs;
    // this toggle is a purely cosmetic local filter.
    var dmRevealRealNames: Boolean = true

    // FOV perception overlay (/story debug fov): false = flat 2D ground wedge,
    // true = solid 3D cone matching the server's pitch-aware inFov.
    var fovCone3D: Boolean = false

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
            dmRevealRealNames = loaded.dmRevealRealNames
            fovCone3D = loaded.fovCone3D
        }
    }

    fun save() {
        val data = StoryConfigData(
            modEnabled, messageVanishTime, dialogueScale, dialogueYOffset,
            useBubbleRenderer, use3DAudio, maxAudioDistance, minAudioDistance,
            combatParryWindowScale, combatSlowMoEnabled, combatScreenShakeIntensity,
            dmRevealRealNames, fovCone3D
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
        val combatScreenShakeIntensity: Float = 1.0f,
        val dmRevealRealNames: Boolean = true,
        val fovCone3D: Boolean = false,
    )
}