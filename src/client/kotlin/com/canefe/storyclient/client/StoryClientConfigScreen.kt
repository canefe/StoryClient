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

        general.addEntry(
            entryBuilder.startDoubleField(Text.literal("Dialogue Box Scale"), StoryClientConfig.dialogueScale)
                .setDefaultValue(1.0)
                .setMin(0.5)
                .setMax(3.0)
                .setSaveConsumer { newValue ->
                    StoryClientConfig.dialogueScale = newValue
                    StoryClientConfig.save()
                    StoryClientConfig.load()
                }
                .setTooltip(Text.literal("Scale factor for dialogue box size (0.5x to 3.0x)"))
                .build()
        )

        general.addEntry(
            entryBuilder.startIntField(Text.literal("Dialogue Box Y Position"), StoryClientConfig.dialogueYOffset)
                .setDefaultValue(80)
                .setMin(0)
                .setMax(300)
                .setSaveConsumer { newValue ->
                    StoryClientConfig.dialogueYOffset = newValue
                    StoryClientConfig.save()
                    StoryClientConfig.load()
                }
                .setTooltip(Text.literal("Distance from bottom of screen (0 = bottom, higher = more up)"))
                .build()
        )


        return builder.build()
    }
}