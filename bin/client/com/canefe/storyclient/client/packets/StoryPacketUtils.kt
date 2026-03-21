package com.canefe.storyclient.client.packets

/**
 * Utility functions for working with Story packets.
 * Includes parsers and converters for migrating from existing systems.
 */
object StoryPacketUtils {

    /**
     * Parse a legacy <npc_typing> message into an NPCTypingPacket.
     * This helps bridge the gap between the current message-based system
     * and the future packet-based system.
     *
     * Message format: <npc_typing>color:#HEXCODEid:uuid:message<npc_typing_end>
     *                 or <npc_typing>uuid:message<npc_typing_end>
     *
     * @param message The raw message string
     * @return NPCTypingPacket or null if parsing fails
     */
    fun parseTypingMessage(message: String): NPCTypingPacket? {
        if (!message.contains("<npc_typing>")) return null

        try {
            val tagContent = message.substringAfter("<npc_typing>").substringBefore("<npc_typing_end>")

            // Check for color format
            if (tagContent.startsWith("color:")) {
                val colorEndIndex = tagContent.indexOf("id:")
                if (colorEndIndex > 0) {
                    val color = tagContent.substring(6, colorEndIndex)
                    val remainingContent = tagContent.substring(colorEndIndex + 3)

                    val parts = remainingContent.split(":", limit = 2)
                    if (parts.size == 2) {
                        return NPCTypingPacket(
                            npcId = parts[0],
                            text = parts[1],
                            color = color,
                            isComplete = false
                        )
                    }
                }
            } else {
                // Old format without color
                val parts = tagContent.split(":", limit = 2)
                if (parts.size == 2) {
                    return NPCTypingPacket(
                        npcId = parts[0],
                        text = parts[1],
                        color = null,
                        isComplete = false
                    )
                }
            }
        } catch (e: Exception) {
            println("Error parsing typing message: ${e.message}")
        }

        return null
    }

    /**
     * Parse a legacy <npc_typing_end> message into a DialogueStatePacket.
     *
     * Message format: <npc_typing_end>id:uuid or just <npc_typing_end>
     *
     * @param message The raw message string
     * @return DialogueStatePacket or null if parsing fails
     */
    fun parseTypingEndMessage(message: String): DialogueStatePacket? {
        if (!message.contains("<npc_typing_end>")) return null

        try {
            val endContent = message.substringAfter("<npc_typing_end>")

            if (endContent.startsWith("id:")) {
                val npcId = endContent.substringAfter("id:").substringBefore("<").trim()
                if (npcId.isNotEmpty()) {
                    return DialogueStatePacket(
                        npcId = npcId,
                        state = DialogueStatePacket.DialogueState.END
                    )
                }
            }
        } catch (e: Exception) {
            println("Error parsing typing end message: ${e.message}")
        }

        return null
    }

    /**
     * Convert an NPCTypingPacket back to legacy message format.
     * Useful for backwards compatibility during migration.
     *
     * @param packet The packet to convert
     * @return Legacy message string
     */
    fun typingPacketToMessage(packet: NPCTypingPacket): String {
        return if (packet.color != null) {
            "<npc_typing>color:${packet.color}id:${packet.npcId}:${packet.text}<npc_typing_end>"
        } else {
            "<npc_typing>${packet.npcId}:${packet.text}<npc_typing_end>"
        }
    }

    /**
     * Convert a DialogueStatePacket to legacy message format.
     *
     * @param packet The packet to convert
     * @return Legacy message string
     */
    fun dialogueStatePacketToMessage(packet: DialogueStatePacket): String {
        return when (packet.state) {
            DialogueStatePacket.DialogueState.END -> "<npc_typing_end>id:${packet.npcId}"
            else -> "" // Other states don't have legacy equivalents
        }
    }

    /**
     * Detect audio format from byte array header.
     *
     * @param data The audio data
     * @return Detected audio format or null
     */
    fun detectAudioFormat(data: ByteArray): NPCAudioPacket.AudioFormat? {
        if (data.size < 4) return null

        return when {
            // WAV: "RIFF" header
            data[0] == 'R'.code.toByte() &&
            data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() &&
            data[3] == 'F'.code.toByte() -> NPCAudioPacket.AudioFormat.WAV

            // MP3: Check for ID3 tag or sync word
            (data[0] == 'I'.code.toByte() &&
             data[1] == 'D'.code.toByte() &&
             data[2] == '3'.code.toByte()) ||
            (data[0].toInt() == 0xFF && (data[1].toInt() and 0xE0) == 0xE0) ->
                NPCAudioPacket.AudioFormat.MP3

            // OGG: "OggS" header
            data[0] == 'O'.code.toByte() &&
            data[1] == 'g'.code.toByte() &&
            data[2] == 'g'.code.toByte() &&
            data[3] == 'S'.code.toByte() -> NPCAudioPacket.AudioFormat.OGG

            else -> null
        }
    }

    /**
     * Validate a packet before sending or processing.
     *
     * @param packet The packet to validate
     * @return true if packet is valid
     */
    fun validatePacket(packet: StoryPacket): Boolean {
        return when (packet) {
            is NPCTypingPacket -> {
                packet.npcId.isNotEmpty() && packet.text.isNotEmpty()
            }
            is NPCAudioPacket -> {
                packet.npcId.isNotEmpty() &&
                packet.audioData.isNotEmpty() &&
                packet.chunkIndex >= 0 &&
                packet.totalChunks > 0 &&
                packet.chunkIndex < packet.totalChunks
            }
            is NPCMetadataPacket -> {
                packet.npcId.isNotEmpty() &&
                (packet.name != null || packet.avatar != null || packet.color != null)
            }
            is DialogueStatePacket -> {
                packet.npcId.isNotEmpty()
            }
            else -> true // Unknown packet types pass validation
        }
    }

    /**
     * Create a test packet for debugging.
     *
     * @param packetType The type of test packet to create
     * @return A test packet
     */
    fun createTestPacket(packetType: String): StoryPacket? {
        return when (packetType) {
            "npc_typing" -> NPCTypingPacket(
                npcId = "test-npc-uuid",
                text = "🧙 Merlin\nHello, traveler! Welcome to my tower.",
                color = "FFD700",
                isComplete = false
            )
            "npc_audio" -> NPCAudioPacket(
                npcId = "test-npc-uuid",
                audioData = ByteArray(1024),
                audioFormat = NPCAudioPacket.AudioFormat.WAV,
                chunkIndex = 0,
                totalChunks = 1,
                isLastChunk = true
            )
            "npc_metadata" -> NPCMetadataPacket(
                npcId = "test-npc-uuid",
                name = "Merlin",
                avatar = "🧙",
                color = "FFD700"
            )
            "dialogue_state" -> DialogueStatePacket(
                npcId = "test-npc-uuid",
                state = DialogueStatePacket.DialogueState.START
            )
            else -> null
        }
    }
}
