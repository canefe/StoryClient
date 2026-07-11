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
            entryBuilder.startDoubleField(Text.literal("Bubble Y Offset (Blocks)"), StoryClientConfig.bubbleYOffset)
                .setDefaultValue(0.0)
                .setMin(-3.0)
                .setMax(3.0)
                .setSaveConsumer { newValue ->
                    StoryClientConfig.bubbleYOffset = newValue
                    StoryClientConfig.save()
                    StoryClientConfig.load()
                }
                .setTooltip(Text.literal("Extra height (in blocks) for dialogue bubbles above NPCs. Only applies when Use Bubble Renderer is on."))
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

        general.addEntry(
            entryBuilder.startBooleanToggle(Text.literal("Bubble Text Outline"), StoryClientConfig.bubbleTextOutline)
                .setDefaultValue(false)
                .setSaveConsumer { newValue ->
                    StoryClientConfig.bubbleTextOutline = newValue
                    StoryClientConfig.save()
                    StoryClientConfig.load()
                }
                .setTooltip(Text.literal("Draw a black outline around NPC bubble dialogue and action text"))
                .build()
        )

        general.addEntry(
            entryBuilder.startBooleanToggle(Text.literal("Bubble Text Shadow"), StoryClientConfig.bubbleTextShadow)
                .setDefaultValue(false)
                .setSaveConsumer { newValue ->
                    StoryClientConfig.bubbleTextShadow = newValue
                    StoryClientConfig.save()
                    StoryClientConfig.load()
                }
                .setTooltip(Text.literal("Draw a drop-shadow under NPC bubble dialogue and action text"))
                .build()
        )

        // Clickable "Reset tip progress" link — runs the client command that
        // clears the one-time-tip seen-set (cloth has no push-button, so a
        // text-description with a RUN_COMMAND click event is the in-framework way).
        val resetTipsText = Text.literal("§e§n[Reset tip progress]")
            .setStyle(
                net.minecraft.text.Style.EMPTY
                    .withClickEvent(
                        net.minecraft.text.ClickEvent(
                            net.minecraft.text.ClickEvent.Action.RUN_COMMAND,
                            "/storyclient-tips-reset",
                        ),
                    )
                    .withHoverEvent(
                        net.minecraft.text.HoverEvent(
                            net.minecraft.text.HoverEvent.Action.SHOW_TEXT,
                            Text.literal("Re-show all one-time tips."),
                        ),
                    ),
            )
        general.addEntry(entryBuilder.startTextDescription(resetTipsText).build())

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

        // Note: per-character voice DSP (pitch/gain/low-pass/tone) is applied
        // SERVER-SIDE by StoryMC (VoiceFxProcessor) using each character's
        // chargen-sampled fx, so the client no longer exposes voice DSP sliders.

        return builder.build()
    }
}