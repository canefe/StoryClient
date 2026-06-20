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
                .setMin(0.1)
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

        general.addEntry(
            entryBuilder.startBooleanToggle(Text.literal("Use Bubble Renderer (Above NPCs)"), StoryClientConfig.useBubbleRenderer)
                .setDefaultValue(false)
                .setSaveConsumer { newValue ->
                    StoryClientConfig.useBubbleRenderer = newValue
                    StoryClientConfig.save()
                    StoryClientConfig.load()
                }
                .setTooltip(Text.literal("Show dialogue bubbles above NPC entities instead of on screen HUD"))
                .build()
        )

        general.addEntry(
            entryBuilder.startBooleanToggle(Text.literal("FOV Overlay: 3D Cone"), StoryClientConfig.fovCone3D)
                .setDefaultValue(false)
                .setSaveConsumer { newValue ->
                    StoryClientConfig.fovCone3D = newValue
                    StoryClientConfig.save()
                    StoryClientConfig.load()
                }
                .setTooltip(Text.literal("Render the /story debug fov perception overlay as a solid 3D cone (follows head pitch) instead of a flat 2D ground wedge"))
                .build()
        )

        // Audio Category
        val audio: ConfigCategory = builder.getOrCreateCategory(Text.literal("Audio"))

        audio.addEntry(
            entryBuilder.startBooleanToggle(Text.literal("Use 3D Positional Audio"), StoryClientConfig.use3DAudio)
                .setDefaultValue(true)
                .setSaveConsumer { newValue ->
                    StoryClientConfig.use3DAudio = newValue
                    StoryClientConfig.save()
                    StoryClientConfig.load()
                }
                .setTooltip(Text.literal("Play NPC voices with 3D positional audio (distance attenuation and stereo panning)"))
                .build()
        )

        audio.addEntry(
            entryBuilder.startDoubleField(Text.literal("Maximum Audio Distance"), StoryClientConfig.maxAudioDistance)
                .setDefaultValue(32.0)
                .setMin(8.0)
                .setMax(64.0)
                .setSaveConsumer { newValue ->
                    StoryClientConfig.maxAudioDistance = newValue
                    StoryClientConfig.save()
                    StoryClientConfig.load()
                }
                .setTooltip(Text.literal("Maximum distance (in blocks) to hear NPC audio"))
                .build()
        )

        audio.addEntry(
            entryBuilder.startDoubleField(Text.literal("Minimum Audio Distance"), StoryClientConfig.minAudioDistance)
                .setDefaultValue(2.0)
                .setMin(0.0)
                .setMax(8.0)
                .setSaveConsumer { newValue ->
                    StoryClientConfig.minAudioDistance = newValue
                    StoryClientConfig.save()
                    StoryClientConfig.load()
                }
                .setTooltip(Text.literal("Distance (in blocks) before audio volume attenuation starts"))
                .build()
        )

        return builder.build()
    }
}