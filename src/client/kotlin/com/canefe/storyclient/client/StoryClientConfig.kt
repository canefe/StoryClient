package com.canefe.storyclient.client

import com.google.gson.Gson
import java.io.File

object StoryClientConfig {
    var modEnabled = true
    var messageVanishTime: Double = 10000.0

    private val gson = Gson()
    private val configFile = File("config/storyclient.json")

    fun load() {
        if (configFile.exists()) {
            val json = configFile.readText()
            val loaded = gson.fromJson(json, StoryConfigData::class.java)
            modEnabled = loaded.modEnabled
            messageVanishTime = loaded.messageVanishTime
        }
    }

    fun save() {
        val data = StoryConfigData(modEnabled, messageVanishTime)
        configFile.parentFile.mkdirs()
        configFile.writeText(gson.toJson(data))
    }

    private data class StoryConfigData(
        val modEnabled: Boolean = true,
        val messageVanishTime: Double
    )
}