package com.canefe.storyclient.client

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.client.MinecraftClient
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream
import java.util.ConcurrentModificationException
import javax.sound.sampled.*
import javazoom.jl.player.Player
import net.minecraft.sound.SoundCategory
import kotlin.math.pow

class NPCMessageParserClient : ClientModInitializer {
    companion object {
        private var audioClip: Clip? = null
        private val chunkBuffer = mutableMapOf<String, ChunkData>()
        private val activePositionalAudio = mutableMapOf<String, PositionalAudioController>()
    }

    // Data class to hold chunked audio data
    data class ChunkData(
        val audioId: String,
        val totalChunks: Int,
        val receivedChunks: MutableMap<Int, ByteArray> = mutableMapOf()
    )

    // Define the custom payload for audio data
    data class AudioPayload(val audioData: ByteArray) : CustomPayload {
        companion object {
            val ID = CustomPayload.Id<AudioPayload>(Identifier.of("story", "play_audio"))
            val CODEC = PacketCodec.of<PacketByteBuf, AudioPayload>(
                { value, buf -> buf.writeByteArray(value.audioData) },
                { buf ->
                    // Read all remaining bytes instead of expecting a size prefix
                    val remainingBytes = buf.readableBytes()
                    val audioData = ByteArray(remainingBytes)
                    buf.readBytes(audioData)
                    AudioPayload(audioData)
                }
            )
        }

        override fun getId(): CustomPayload.Id<out CustomPayload> = ID

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as AudioPayload
            return audioData.contentEquals(other.audioData)
        }

        override fun hashCode(): Int {
            return audioData.contentHashCode()
        }
    }

    override fun onInitializeClient() {
        StoryClientConfig.load()

        // Register the AudioPayload type first (for modern clients)
        PayloadTypeRegistry.playS2C().register(AudioPayload.ID, AudioPayload.CODEC)

        // Register the modern CustomPayload receiver
        ClientPlayNetworking.registerGlobalReceiver(AudioPayload.ID) { payload, context ->
            try {
                println("📦 Received audio payload! Size: ${payload.audioData.size} bytes")

                context.client().execute {
                    val (npcUuid, audioBytes) = extractNpcHeader(payload.audioData)
                    println("🎵 Processing audio data... npcUuid=$npcUuid, audioSize=${audioBytes.size}")

                    // Notify TypingManager that voice arrived — releases pending dialogue
                    if (npcUuid != null) {
                        TypingManager.onVoiceReceived(npcUuid)
                    }

                    playAudio(audioBytes, npcUuid)
                }
            } catch (e: Exception) {
                println("❌ Error processing audio packet: ${e.message}")
                e.printStackTrace()
            }
        }

        println("✅ Registered modern audio receiver")

        // Register Story nearby-NPCs payload + receiver (powers the action wheel)
        PayloadTypeRegistry.playS2C().register(
            com.canefe.storyclient.client.wheel.NearbyNPCPayload.ID,
            com.canefe.storyclient.client.wheel.NearbyNPCPayload.CODEC,
        )
        ClientPlayNetworking.registerGlobalReceiver(
            com.canefe.storyclient.client.wheel.NearbyNPCPayload.ID,
        ) { payload, _ ->
            com.canefe.storyclient.client.wheel.NearbyNPCCache.replaceAll(payload.entries)
        }

        // Recognition set (s2c) — drives Helix-style nametag real name resolution
        PayloadTypeRegistry.playS2C().register(
            com.canefe.storyclient.client.recognition.RecognitionSetPayload.ID,
            com.canefe.storyclient.client.recognition.RecognitionSetPayload.CODEC,
        )
        ClientPlayNetworking.registerGlobalReceiver(
            com.canefe.storyclient.client.recognition.RecognitionSetPayload.ID,
        ) { payload, _ ->
            com.canefe.storyclient.client.recognition.RecognitionCache.replaceAll(payload.known)
        }

        // Squad list payload (s2c) — drives the command-mode HUD
        PayloadTypeRegistry.playS2C().register(
            com.canefe.storyclient.client.squad.SquadListPayload.ID,
            com.canefe.storyclient.client.squad.SquadListPayload.CODEC,
        )
        PayloadTypeRegistry.playC2S().register(
            com.canefe.storyclient.client.squad.SquadOrderPayload.ID,
            com.canefe.storyclient.client.squad.SquadOrderPayload.CODEC,
        )
        ClientPlayNetworking.registerGlobalReceiver(
            com.canefe.storyclient.client.squad.SquadListPayload.ID,
        ) { payload, _ ->
            com.canefe.storyclient.client.squad.SquadListCache.replaceAll(payload.entries)
        }

        // Puppet group payload (s2c) and command payload (c2s)
        PayloadTypeRegistry.playS2C().register(
            com.canefe.storyclient.client.puppet.PuppetGroupPayload.ID,
            com.canefe.storyclient.client.puppet.PuppetGroupPayload.CODEC,
        )
        PayloadTypeRegistry.playC2S().register(
            com.canefe.storyclient.client.puppet.PuppetCommandPayload.ID,
            com.canefe.storyclient.client.puppet.PuppetCommandPayload.CODEC,
        )
        ClientPlayNetworking.registerGlobalReceiver(
            com.canefe.storyclient.client.puppet.PuppetGroupPayload.ID,
        ) { payload, _ ->
            com.canefe.storyclient.client.puppet.PuppetState.replaceAll(payload.names)
        }

        // NPC perception popup (s2c)
        PayloadTypeRegistry.playS2C().register(
            com.canefe.storyclient.client.perception.NpcPerceptionPayload.ID,
            com.canefe.storyclient.client.perception.NpcPerceptionPayload.CODEC,
        )
        ClientPlayNetworking.registerGlobalReceiver(
            com.canefe.storyclient.client.perception.NpcPerceptionPayload.ID,
        ) { payload, _ ->
            println("[StoryClient] NpcPerception received: uuid=${payload.npcUuid} type=${payload.type} label=${payload.perceivedLabel}")
            com.canefe.storyclient.client.perception.PerceptionPopupRenderer.onPerception(
                payload.npcUuid,
                payload.perceivedLabel,
                payload.type,
            )
        }

        // Action wheel keybind (default: R, hold)
        val wheelKey =
            net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(
                net.minecraft.client.option.KeyBinding(
                    "key.storyclient.action_wheel",
                    org.lwjgl.glfw.GLFW.GLFW_KEY_R,
                    "key.categories.storyclient",
                ),
            )

        // Squad command-mode toggle (default: Y)
        val squadCommandKey =
            net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(
                net.minecraft.client.option.KeyBinding(
                    "key.storyclient.squad_command",
                    org.lwjgl.glfw.GLFW.GLFW_KEY_Y,
                    "key.categories.storyclient",
                ),
            )

        // Register tick event for TypingManager
        ClientTickEvents.END_CLIENT_TICK.register {
            TypingManager.tick()

            // Wheel: open on press, close+commit on release.
            // R without looking at an NPC, while in puppet mode, exits puppet mode.
            val isDown = wheelKey.isPressed
            val wasOpen = com.canefe.storyclient.client.wheel.ActionWheelHud.open
            if (isDown && !wasOpen) {
                val opened = com.canefe.storyclient.client.wheel.ActionWheelHud.tryOpen()
                if (!opened && com.canefe.storyclient.client.puppet.PuppetState.inPuppetMode) {
                    com.canefe.storyclient.client.puppet.PuppetCommandPayload.clear()
                    com.canefe.storyclient.client.puppet.PuppetState.localClear()
                }
            } else if (!isDown && wasOpen) {
                com.canefe.storyclient.client.wheel.ActionWheelHud.closeAndCommit()
            }

            // Squad command-mode toggle: edge-triggered on Y press.
            while (squadCommandKey.wasPressed()) {
                com.canefe.storyclient.client.squad.SquadCommandState.toggleCommandMode()
            }
        }

        // HUD render for the wheel + puppet + squad overlays
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register { ctx, _ ->
            com.canefe.storyclient.client.puppet.PuppetHud.render(ctx)
            com.canefe.storyclient.client.squad.SquadListHud.render(ctx)
            com.canefe.storyclient.client.wheel.ActionWheelHud.render(ctx)
        }

        // Register world render event for BubbleRenderer + squad badges + formation preview
        WorldRenderEvents.AFTER_ENTITIES.register { context ->
            BubbleRenderer.render(context)
            com.canefe.storyclient.client.perception.PerceptionPopupRenderer.render(context)
            com.canefe.storyclient.client.squad.SquadBadgeRenderer.render(context)
            com.canefe.storyclient.client.squad.SquadFormationPreviewRenderer.render(context)
            com.canefe.storyclient.client.puppet.PuppetCursorRenderer.render(context)
            com.canefe.storyclient.client.recognition.HelixNametagRenderer.render(context)
        }

        // Fix Dialogue command (that removes session, removes buggy dialog box)
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            // Toggle Helix nametag diagnostic logging
            dispatcher.register(
                ClientCommandManager.literal("helixdebug")
                    .executes { ctx ->
                        val r = com.canefe.storyclient.client.recognition.HelixNametagRenderer
                        r.debug = !r.debug
                        ctx.source.sendFeedback(net.minecraft.text.Text.literal(
                            "Helix nametag debug: ${if (r.debug) "ON (logs to console once/sec)" else "OFF"}"
                        ))
                        val cacheSize = com.canefe.storyclient.client.wheel.NearbyNPCCache.all().size
                        val recogSize = com.canefe.storyclient.client.recognition.RecognitionCache.size()
                        ctx.source.sendFeedback(net.minecraft.text.Text.literal(
                            "  NearbyNPCCache=$cacheSize, RecognitionCache=$recogSize"
                        ))
                        1
                    })

            dispatcher.register(
                ClientCommandManager.literal("fixdialogue")
                    .executes {
                        // Check if the player is in a conversation
                        // Remove the session
                        TypingManager.finishAllSessions()
                        NPCDialogueHud.endDialogue("a")
                        BubbleRenderer.removeAllBubbles()

                        1
                    })

            // Test dialogue command
            dispatcher.register(
                ClientCommandManager.literal("testdialogue")
                    .executes { context ->
                        val player = context.source.player
                        val world = context.source.world

                        // Find nearby entities
                        val nearbyEntities = try {
                            world.getOtherEntities(
                                player,
                                net.minecraft.util.math.Box.of(player.pos, 32.0, 32.0, 32.0)
                            ) { entity ->
                                entity is net.minecraft.entity.LivingEntity &&
                                entity.isAlive
                            }
                        } catch (_: ConcurrentModificationException) {
                            emptyList()
                        }

                        if (nearbyEntities.isEmpty()) {
                            player.sendMessage(net.minecraft.text.Text.literal("No nearby entities found to test with!"), false)
                            return@executes 0
                        }

                        // Pick a random entity
                        val testEntity = nearbyEntities.random()
                        val entityUuid = testEntity.uuidAsString
                        val entityName = testEntity.name.string

                        // Random dialogues for testing
                        val dialogues = listOf(
                            "Wizard Aldric:Greetings, traveler! *waves staff mysteriously* I sense great power within you.",
                            "Alpha Wolf:The forest speaks of your arrival. *sniffs the air* You carry an interesting scent.",
                            "Knight Commander:*salutes* Well met, adventurer! The realm needs heroes like you.",
                            "Forest Sprite:*giggles and twirls* Oh my! A visitor! *sparkles appear* How delightful!",
                            "Grumpy Goblin:Oi! What're you doing in me territory? *crosses arms* Better have a good reason!",
                            "Wise Owl:*hoots softly* Whoo... whoo... The old stories tell of one such as you. *ruffles feathers*",
                            "Ancient Dragon:Mortal... *smoke curls from nostrils* You dare approach my domain? Speak your purpose!",
                            "Mysterious Merchant:*adjusts hood* Ah, a customer! *rubs hands together* I have... special wares for special people."
                        )

                        val randomDialogue = dialogues.random()
                        val testColor = listOf("FFCC44", "88FF88", "FF8844", "8888FF", "FF88FF").random()

                        // Simulate the server message format
                        val simulatedMessage = "<npc_typing>color:#${testColor}id:${entityUuid}:${randomDialogue}<npc_typing_end>"

                        player.sendMessage(
                            net.minecraft.text.Text.literal("§aSimulating dialogue from: §e$entityName §7(UUID: ${entityUuid.take(8)}...)"),
                            false
                        )

                        // Send to TypingManager
                        TypingManager.onIncomingServerMessage(simulatedMessage)

                        // Schedule end message after 5 seconds using a simple counter
                        Thread {
                            Thread.sleep(5000) // 5 seconds
                            val endMessage = "<npc_typing_end>id:${entityUuid}"
                            MinecraftClient.getInstance().execute {
                                TypingManager.onIncomingServerMessage(endMessage)
                            }
                        }.start()

                        1
                    })
        }
    }

    private fun extractNpcHeader(raw: ByteArray): Pair<String?, ByteArray> {
        if (raw.isEmpty()) return null to raw

        var offset = 0
        val hasNpc = raw[offset++].toInt() != 0 // 0 = no npc, 1 = has npc

        if (!hasNpc) {
            // old behavior, just one extra flag byte
            if (offset >= raw.size) return null to ByteArray(0)
            return null to raw.copyOfRange(offset, raw.size)
        }

        if (raw.size < offset + 4) {
            println("❌ NPC header malformed (no uuid length)")
            return null to raw // fallback: treat as old packet
        }

        val len =
            ((raw[offset++].toInt() and 0xFF) shl 24) or
                    ((raw[offset++].toInt() and 0xFF) shl 16) or
                    ((raw[offset++].toInt() and 0xFF) shl 8) or
                    (raw[offset++].toInt() and 0xFF)

        if (len <= 0 || raw.size < offset + len) {
            println("❌ NPC header malformed (uuidLen=$len, size=${raw.size})")
            return null to raw // fallback
        }

        val uuidStr = String(raw, offset, len, Charsets.UTF_8)
        offset += len

        if (offset > raw.size) {
            println("❌ NPC header offset beyond array")
            return uuidStr to ByteArray(0)
        }

        val audioBytes = raw.copyOfRange(offset, raw.size)
        return uuidStr to audioBytes
    }

    private fun stopCurrentAudio() {
        try {
            audioClip?.let { clip ->
                if (clip.isRunning) {
                    clip.stop()
                    println("🛑 Stopped currently playing audio")
                }
                clip.close()
                audioClip = null
            }

            // Stop all positional audio
            activePositionalAudio.values.forEach { it.stop() }
            activePositionalAudio.clear()
        } catch (e: Exception) {
            println("❌ Error stopping current audio: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun playAudio(audioData: ByteArray, npcUuidFromPacket: String?) {
        try {
            // Stop any currently playing audio first
            //stopCurrentAudio()

            println("🎵 Received audio data: ${audioData.size} bytes")

            // Print first few bytes for debugging
            val debugBytes = audioData.take(16).map { "%02X".format(it) }.joinToString(" ")
            println("🔍 First 16 bytes: $debugBytes")

            // Detect if this is chunked data or raw audio data
            val processedData = if (isChunkedAudioData(audioData)) {
                println("🔍 Detected chunked audio data format")
                parseChunkedAudioData(audioData)
            } else {
                println("🔍 Detected single packet audio data (< 50KB)")
                audioData // Use raw data directly
            }

            if (processedData != null) {
                println("🎵 Processing audio file: ${processedData.size} bytes")

                // Show first bytes of processed audio data
                val audioDebugBytes = processedData.take(16).map { "%02X".format(it) }.joinToString(" ")
                println("🔍 First 16 bytes of processed audio: $audioDebugBytes")

                // Check if we should use positional audio
                val npcUuid = if (StoryClientConfig.use3DAudio) {
                    npcUuidFromPacket
                } else null

                if (npcUuid != null) {
                    println("🎯 Using 3D positional audio for NPC: $npcUuid")
                } else if (StoryClientConfig.use3DAudio) {
                    println("⚠️ 3D audio enabled but no active NPC found, using global playback")
                }

                // Try to detect the audio format
                when {
                    isWAV(processedData) -> {
                        println("🎵 WAV format detected")
                        if (npcUuid != null) {
                            playPositionalWAV(processedData, npcUuid)
                        } else {
                            playWAVAudio(processedData)
                        }
                    }
                    isMP3(processedData) -> {
                        println("🎵 MP3 format detected")
                        if (npcUuid != null) {
                            playPositionalMP3(processedData, npcUuid)
                        } else {
                            playMP3Audio(processedData)
                        }
                    }
                    else -> {
                        println("🎵 Unknown format - trying as raw PCM with different configurations")
                        playRawAudio(processedData)
                    }
                }
            } else {
                println("🔍 Chunk not complete yet, waiting for more chunks...")
            }
        } catch (e: Exception) {
            println("❌ Error playing audio: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun isChunkedAudioData(audioData: ByteArray): Boolean {
        try {
            if (audioData.size < 12) return false

            // Try to read what would be the audio ID length
            val audioIdLength = ((audioData[0].toInt() and 0xFF) shl 24) or
                               ((audioData[1].toInt() and 0xFF) shl 16) or
                               ((audioData[2].toInt() and 0xFF) shl 8) or
                               (audioData[3].toInt() and 0xFF)

            // Chunked data should have a reasonable audio ID length (1-100 chars)
            // and the rest of the structure should make sense
            if (audioIdLength > 0 && audioIdLength <= 100 && 4 + audioIdLength + 8 < audioData.size) {
                // Check if we can read a valid audio ID
                val audioIdBytes = audioData.sliceArray(4 until 4 + audioIdLength)
                val audioId = try {
                    String(audioIdBytes, Charsets.UTF_8)
                } catch (e: Exception) {
                    return false
                }

                // Audio ID should be printable characters
                if (audioId.all { it.isLetterOrDigit() || it in "-_." }) {
                    println("🔍 Potential chunked format detected with audio ID: $audioId")
                    return true
                }
            }

            // If it doesn't look like chunked format, check if it looks like raw audio
            when {
                isWAV(audioData) -> {
                    println("🔍 Detected WAV header - treating as single packet")
                    return false
                }
                isMP3(audioData) -> {
                    println("🔍 Detected MP3 header - treating as single packet")
                    return false
                }
                else -> {
                    // If it's not clearly WAV or MP3, and doesn't look like chunked format,
                    // assume it's raw audio data sent as single packet
                    println("🔍 No clear format detected - assuming single packet raw audio")
                    return false
                }
            }
        } catch (e: Exception) {
            println("🔍 Error detecting chunked format: ${e.message}")
            return false
        }
    }

    private fun parseChunkedAudioData(audioData: ByteArray): ByteArray? {
        try {
            if (audioData.size < 12) { // Minimum size for header
                println("❌ Audio data too small for chunked format")
                return null
            }

            var offset = 0

            // Read audio ID length (4 bytes)
            val audioIdLength = ((audioData[offset++].toInt() and 0xFF) shl 24) or
                               ((audioData[offset++].toInt() and 0xFF) shl 16) or
                               ((audioData[offset++].toInt() and 0xFF) shl 8) or
                               (audioData[offset++].toInt() and 0xFF)

            println("🔍 Audio ID length: $audioIdLength")

            if (audioIdLength <= 0 || audioIdLength > 100 || offset + audioIdLength > audioData.size) {
                println("❌ Invalid audio ID length: $audioIdLength")
                return null
            }

            // Read audio ID
            val audioIdBytes = audioData.sliceArray(offset until offset + audioIdLength)
            val audioId = String(audioIdBytes, Charsets.UTF_8)
            offset += audioIdLength

            println("🔍 Audio ID: $audioId")

            if (offset + 8 > audioData.size) {
                println("❌ Not enough data for chunk info")
                return null
            }

            // Read chunk index (4 bytes)
            val chunkIndex = ((audioData[offset++].toInt() and 0xFF) shl 24) or
                            ((audioData[offset++].toInt() and 0xFF) shl 16) or
                            ((audioData[offset++].toInt() and 0xFF) shl 8) or
                            (audioData[offset++].toInt() and 0xFF)

            // Read total chunks (4 bytes)
            val totalChunks = ((audioData[offset++].toInt() and 0xFF) shl 24) or
                             ((audioData[offset++].toInt() and 0xFF) shl 16) or
                             ((audioData[offset++].toInt() and 0xFF) shl 8) or
                             (audioData[offset++].toInt() and 0xFF)

            println("🔍 Chunk $chunkIndex of $totalChunks")

            // Extract chunk data
            val chunkData = audioData.sliceArray(offset until audioData.size)
            println("🔍 Chunk data size: ${chunkData.size} bytes")

            // Get or create chunk buffer entry
            val chunkEntry = chunkBuffer.getOrPut(audioId) {
                ChunkData(audioId, totalChunks)
            }

            // Store this chunk
            chunkEntry.receivedChunks[chunkIndex] = chunkData
            println("🔍 Stored chunk $chunkIndex, now have ${chunkEntry.receivedChunks.size}/$totalChunks chunks")

            // Debug: Print which chunks we have
            val receivedIndices = chunkEntry.receivedChunks.keys.sorted()
            println("🔍 Received chunk indices: $receivedIndices")

            // Check if we have all chunks
            if (chunkEntry.receivedChunks.size == totalChunks) {
                println("✅ All chunks received! Assembling audio file...")

                // Debug: Check for missing chunks in sequence
                val missingChunks = mutableListOf<Int>()
                for (i in 0 until totalChunks) {
                    if (!chunkEntry.receivedChunks.containsKey(i)) {
                        missingChunks.add(i)
                    }
                }

                if (missingChunks.isNotEmpty()) {
                    println("❌ Missing chunks in sequence: $missingChunks")
                    println("🔍 Have chunks: ${chunkEntry.receivedChunks.keys.sorted()}")
                    println("🔍 Expected chunks: ${(0 until totalChunks).toList()}")
                    return null
                }

                // Assemble the complete audio data
                val completeAudio = mutableListOf<Byte>()
                var totalAssembledSize = 0
                for (i in 0 until totalChunks) {
                    val chunk = chunkEntry.receivedChunks[i]
                    if (chunk != null) {
                        completeAudio.addAll(chunk.toList())
                        totalAssembledSize += chunk.size
                        println("🔍 Added chunk $i: ${chunk.size} bytes (total so far: $totalAssembledSize)")
                    } else {
                        println("❌ Missing chunk $i")
                        return null
                    }
                }

                // Clean up the chunk buffer
                chunkBuffer.remove(audioId)

                println("✅ Successfully assembled complete audio: $totalAssembledSize bytes")
                return completeAudio.toByteArray()
            } else {
                println("🔍 Still waiting for chunks. Need $totalChunks, have ${chunkEntry.receivedChunks.size}")
            }

            return null // Not all chunks received yet
        } catch (e: Exception) {
            println("❌ Error parsing chunked audio data: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    private fun playWAVAudio(audioData: ByteArray) {
        try {
            val audioInputStream = AudioSystem.getAudioInputStream(ByteArrayInputStream(audioData))
            println("🎵 WAV Format: ${audioInputStream.format}")

            // Get audio clip
            audioClip = AudioSystem.getClip()
            audioClip?.open(audioInputStream)

            // Set volume based on Minecraft's volume settings
            try {
                val volume = MinecraftClient.getInstance().options.getSoundVolume(SoundCategory.VOICE)
                println("🔊 Voice volume: ${(volume * 100).toInt()}%")

                val gainControl = audioClip?.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl
                if (gainControl != null) {
                    val gain = if (volume > 0.0) {
                        val minGain = gainControl.minimum
                        val maxGain = gainControl.maximum

                        // Use a gentler curve: cubic root instead of square
                        // This keeps lower volumes audible while still providing good range
                        val adjustedVolume = volume.toDouble().pow(0.5) // Square root for gentler curve
                        minGain + (maxGain - minGain) * adjustedVolume.toFloat()
                    } else {
                        gainControl.minimum // Mute
                    }
                    gainControl.value = gain
                    println("🔊 Set WAV audio gain to ${gain} dB (range: ${gainControl.minimum} to ${gainControl.maximum})")
                } else {
                    println("⚠️ No gain control available for WAV audio")
                }
            } catch (e: Exception) {
                println("⚠️ Could not set volume control for WAV audio: ${e.message}")
            }

            // Add line listener to clean up when done
            audioClip?.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP) {
                    audioClip?.close()
                    audioClip = null
                }
            }

            // Play the audio
            audioClip?.start()
            println("✅ WAV audio playback started")

        } catch (e: Exception) {
            println("❌ Error playing WAV audio: ${e.message}")
            // Fall back to raw audio attempt
            playRawAudio(audioData)
        }
    }

    private fun playMP3Audio(audioData: ByteArray) {
        try {
            println("🎵 MP3 detected - attempting to play MP3 file with JLayer")

            // Stop any currently playing audio
            audioClip?.stop()
            audioClip?.close()

            // Use JLayer to play MP3 directly from byte array
            Thread {
                try {
                    val inputStream = ByteArrayInputStream(audioData)
                    val player = Player(inputStream)
                    println("✅ Starting MP3 playback with JLayer")
                    player.play()
                    println("✅ MP3 playback completed")
                } catch (e: Exception) {
                    println("❌ JLayer MP3 playback failed: ${e.message}")
                    e.printStackTrace()

                    // Fallback to trying without ID3 tags
                    try {
                        val audioWithoutID3 = skipID3Tags(audioData)
                        if (audioWithoutID3 != null) {
                            println("🎵 Trying MP3 playback without ID3 tags")
                            val inputStream2 = ByteArrayInputStream(audioWithoutID3)
                            val player2 = Player(inputStream2)
                            player2.play()
                            println("✅ MP3 playback without ID3 completed")
                        } else {
                            println("❌ Could not process MP3 file")
                        }
                    } catch (e2: Exception) {
                        println("❌ MP3 fallback also failed: ${e2.message}")
                    }
                }
            }.start()

        } catch (e: Exception) {
            println("❌ Error in playMP3Audio: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun playPositionalWAV(audioData: ByteArray, npcUuid: String) {
        try {
            println("🎵 Starting positional WAV playback for NPC: $npcUuid")

            // Stop any existing audio controller for this NPC
            activePositionalAudio.remove(npcUuid)?.stop()

            val controller = PositionalAudioController(audioData, npcUuid)
            activePositionalAudio[npcUuid] = controller
            controller.start()

            println("✅ Positional WAV playback started for NPC: $npcUuid")
        } catch (e: Exception) {
            println("❌ Error playing positional WAV: ${e.message}")
            e.printStackTrace()
            println("⚠️ Falling back to global WAV playback")
            playWAVAudio(audioData)
        }
    }

    private fun playPositionalMP3(audioData: ByteArray, npcUuid: String) {
        // MP3 positional audio requires decoding to PCM first
        // For now, fall back to global MP3 playback
        println("⚠️ MP3 positional audio not yet fully supported, using global playback")
        println("   (NPC UUID: $npcUuid)")
        playMP3Audio(audioData)

        // TODO: Implement MP3 decoding to PCM for positional playback
        // This would require:
        // 1. Decode MP3 to WAV/PCM using JLayer
        // 2. Get the decoded PCM data as ByteArray
        // 3. Create AudioFormat from decoded data
        // 4. Use PositionalAudioPlayer with the PCM data
    }

    private fun skipID3Tags(audioData: ByteArray): ByteArray? {
        try {
            if (audioData.size < 10) return null

            // Check for ID3v2 tag
            if (audioData[0] == 0x49.toByte() && audioData[1] == 0x44.toByte() && audioData[2] == 0x33.toByte()) {
                // ID3v2 tag found, calculate size
                val majorVersion = audioData[3].toInt()
                val minorVersion = audioData[4].toInt()
                val flags = audioData[5].toInt()

                // Size is stored in bytes 6-9 as syncsafe integer
                val size = ((audioData[6].toInt() and 0x7F) shl 21) or
                          ((audioData[7].toInt() and 0x7F) shl 14) or
                          ((audioData[8].toInt() and 0x7F) shl 7) or
                          (audioData[9].toInt() and 0x7F)

                val tagSize = size + 10 // +10 for the header itself

                println("🔍 ID3v2.$majorVersion.$minorVersion tag found, size: $tagSize bytes")

                if (tagSize < audioData.size) {
                    return audioData.sliceArray(tagSize until audioData.size)
                }
            }

            return audioData
        } catch (e: Exception) {
            println("❌ Error skipping ID3 tags: ${e.message}")
            return audioData
        }
    }

    private fun playRawAudio(audioData: ByteArray) {
        try {
            // Try different common audio formats
            val commonFormats = listOf(
                // 44.1kHz, 16-bit, stereo
                AudioFormat(44100f, 16, 2, true, false),
                // 44.1kHz, 16-bit, mono
                AudioFormat(44100f, 16, 1, true, false),
                // 22kHz, 16-bit, mono (common for TTS)
                AudioFormat(22050f, 16, 1, true, false),
                // 16kHz, 16-bit, mono (common for TTS)
                AudioFormat(16000f, 16, 1, true, false)
            )

            for (format in commonFormats) {
                try {
                    playPCMAudio(audioData, format)
                    println("✅ Successfully played audio with format: $format")
                    return
                } catch (e: Exception) {
                    println("❌ Failed with format $format: ${e.message}")
                }
            }

            println("❌ Could not play audio with any common format")
        } catch (e: Exception) {
            println("❌ Error in playRawAudio: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun playPCMAudio(audioData: ByteArray, audioFormat: AudioFormat) {
        try {
            val audioInputStream = AudioInputStream(
                ByteArrayInputStream(audioData),
                audioFormat,
                audioData.size.toLong() / audioFormat.frameSize
            )

            // Get audio clip
            audioClip = AudioSystem.getClip()
            audioClip?.open(audioInputStream)

            // Set volume based on Minecraft's volume settings
            try {
                val volume = MinecraftClient.getInstance().options.getSoundVolume(SoundCategory.VOICE)
                val gainControl = audioClip?.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl
                if (gainControl != null) {
                    val gain = if (volume > 0.0) {
                        val minGain = gainControl.minimum
                        val maxGain = gainControl.maximum

                        // Use a gentler curve: square root instead of square
                        // This keeps lower volumes audible while still providing good range
                        val adjustedVolume = volume.toDouble().pow(0.5) // Square root for gentler curve
                        minGain + (maxGain - minGain) * adjustedVolume.toFloat()
                    } else {
                        gainControl.minimum // Mute
                    }
                    gainControl.value = gain
                    println("🔊 Set PCM audio volume to ${(volume * 100).toInt()}% (${gain} dB)")
                } else {
                    println("⚠️ No gain control available for PCM audio")
                }
            } catch (e: Exception) {
                println("⚠️ Could not set volume control for PCM audio: ${e.message}")
            }

            // Add line listener to clean up when done
            audioClip?.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP) {
                    audioClip?.close()
                    audioClip = null
                }
            }

            // Play the audio
            audioClip?.start()
            println("✅ Audio playback started")

        } catch (e: Exception) {
            println("❌ Error playing PCM audio: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun isMP3(data: ByteArray): Boolean {
        if (data.size < 3) return false

        // Check for ID3 tag at the beginning (ID3v2)
        if (data[0] == 0x49.toByte() && data[1] == 0x44.toByte() && data[2] == 0x33.toByte()) {
            return true
        }

        // Check for MP3 frame header (original detection)
        if (data[0] == 0xFF.toByte() && (data[1].toInt() and 0xE0) == 0xE0) {
            return true
        }

        // Check for MP3 frame header later in the file (after ID3 tags)
        for (i in 0 until minOf(data.size - 2, 1024)) {
            if (data[i] == 0xFF.toByte() && (data[i + 1].toInt() and 0xE0) == 0xE0) {
                return true
            }
        }

        return false
    }

    private fun isWAV(data: ByteArray): Boolean {
        // Simple WAV header detection
        return data.size >= 12 &&
               data[0] == 'R'.code.toByte() &&
               data[1] == 'I'.code.toByte() &&
               data[2] == 'F'.code.toByte() &&
               data[3] == 'F'.code.toByte() &&
               data[8] == 'W'.code.toByte() &&
               data[9] == 'A'.code.toByte() &&
               data[10] == 'V'.code.toByte() &&
               data[11] == 'E'.code.toByte()
    }
}

