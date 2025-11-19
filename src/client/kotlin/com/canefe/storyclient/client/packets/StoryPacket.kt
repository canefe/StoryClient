package com.canefe.storyclient.client.packets

/**
 * Base interface for all Story ecosystem packets.
 * Each packet type should implement this interface to define its structure.
 */
interface StoryPacket {
    /**
     * Unique identifier for the packet type.
     * Used for routing packets to appropriate handlers.
     */
    val packetType: String
}

/**
 * Packet containing NPC typing/dialogue information.
 * Future replacement for the current <npc_typing> message-based system.
 */
data class NPCTypingPacket(
    val npcId: String,
    val text: String,
    val color: String? = null,
    val isComplete: Boolean = false
) : StoryPacket {
    override val packetType: String = "npc_typing"
}

/**
 * Packet containing audio data for NPC voices.
 * Future abstraction for the current audio packet system.
 */
data class NPCAudioPacket(
    val npcId: String,
    val audioData: ByteArray,
    val audioFormat: AudioFormat,
    val chunkIndex: Int = 0,
    val totalChunks: Int = 1,
    val isLastChunk: Boolean = true
) : StoryPacket {
    override val packetType: String = "npc_audio"

    enum class AudioFormat {
        WAV,
        MP3,
        OGG
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as NPCAudioPacket

        if (npcId != other.npcId) return false
        if (!audioData.contentEquals(other.audioData)) return false
        if (audioFormat != other.audioFormat) return false
        if (chunkIndex != other.chunkIndex) return false
        if (totalChunks != other.totalChunks) return false
        if (isLastChunk != other.isLastChunk) return false

        return true
    }

    override fun hashCode(): Int {
        var result = npcId.hashCode()
        result = 31 * result + audioData.contentHashCode()
        result = 31 * result + audioFormat.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + totalChunks
        result = 31 * result + isLastChunk.hashCode()
        return result
    }
}

/**
 * Packet for NPC metadata updates (name, avatar, color, etc.)
 */
data class NPCMetadataPacket(
    val npcId: String,
    val name: String? = null,
    val avatar: String? = null,
    val color: String? = null
) : StoryPacket {
    override val packetType: String = "npc_metadata"
}

/**
 * Packet for dialogue state changes (start, end, update)
 */
data class DialogueStatePacket(
    val npcId: String,
    val state: DialogueState
) : StoryPacket {
    override val packetType: String = "dialogue_state"

    enum class DialogueState {
        START,
        TYPING,
        COMPLETE,
        END
    }
}
