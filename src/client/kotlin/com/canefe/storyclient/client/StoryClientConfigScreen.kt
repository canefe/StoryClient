package com.canefe.storyclient.client

import me.shedaniel.clothconfig2.api.ConfigBuilder
import me.shedaniel.clothconfig2.api.ConfigCategory
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

object StoryClientConfigScreen {
    fun create(parent: Screen?): Screen {
        val builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Text.literal("My Mod Settings"))

        val entryBuilder = builder.entryBuilder()
        val general: ConfigCategory = builder.getOrCreateCategory(Text.literal("General"))


        general.addEntry(entryBuilder.startBooleanToggle(Text.literal("Enable NPC Message"), StoryClientConfig.modEnabled)
            .setDefaultValue(StoryClientConfig.modEnabled)
            .setSaveConsumer { newValue ->
                StoryClientConfig.modEnabled = newValue
                StoryClientConfig.save()  // Save immediately after changing
                StoryClientConfig.load()  // Reload the config to apply changes
            }
            .build()
        )

        general.addEntry(
            entryBuilder.startDoubleField(Text.literal("Dialogue Box Vanish Time"), StoryClientConfig.messageVanishTime)
                .setDefaultValue(StoryClientConfig.messageVanishTime)
                .setSaveConsumer { newValue ->
                    StoryClientConfig.messageVanishTime = newValue
                    StoryClientConfig.save()  // Save immediately after changing
                    StoryClientConfig.load()  // Reload the config to apply changes
                }
                .build()
        )


        return builder.build()
    }
}