package com.canefe.storyclient.client.packets

/**
 * Interface for handling specific types of Story packets.
 * Implement this interface to process packets of a specific type.
 */
interface StoryPacketHandler<T : StoryPacket> {
    /**
     * The packet type this handler is responsible for.
     * Should match the packetType field of the packet class.
     */
    val handledPacketType: String

    /**
     * Called when a packet of the handled type is received.
     * @param packet The received packet
     */
    fun handlePacket(packet: T)

    /**
     * Optional: Called when packet handling fails.
     * Override to implement custom error handling.
     */
    fun onError(packet: T, error: Exception) {
        println("Error handling packet ${packet.packetType}: ${error.message}")
        error.printStackTrace()
    }
}

/**
 * Example handler for NPC typing packets.
 * This would eventually replace the message-based system in TypingManager.
 */
class NPCTypingPacketHandler : StoryPacketHandler<NPCTypingPacket> {
    override val handledPacketType: String = "npc_typing"

    override fun handlePacket(packet: NPCTypingPacket) {
        println("📝 Received NPC typing packet: npcId=${packet.npcId}, text=${packet.text.take(50)}...")

        // Future implementation would call:
        // TypingManager.handleTypingPacket(packet)
        // or integrate directly with BubbleRenderer/NPCDialogueHud
    }
}

/**
 * Example handler for NPC audio packets.
 * This would eventually replace/abstract the current audio packet system.
 */
class NPCAudioPacketHandler : StoryPacketHandler<NPCAudioPacket> {
    override val handledPacketType: String = "npc_audio"

    override fun handlePacket(packet: NPCAudioPacket) {
        println("🔊 Received NPC audio packet: npcId=${packet.npcId}, format=${packet.audioFormat}, chunk=${packet.chunkIndex}/${packet.totalChunks}")

        // Future implementation would call:
        // NPCMessageParserClient.handleAudioPacket(packet)
    }
}

/**
 * Handler for NPC metadata updates.
 */
class NPCMetadataPacketHandler : StoryPacketHandler<NPCMetadataPacket> {
    override val handledPacketType: String = "npc_metadata"

    override fun handlePacket(packet: NPCMetadataPacket) {
        println("ℹ️ Received NPC metadata packet: npcId=${packet.npcId}, name=${packet.name}, avatar=${packet.avatar}")

        // Future implementation would update NPC registry or cache
    }
}

/**
 * Handler for dialogue state changes.
 */
class DialogueStatePacketHandler : StoryPacketHandler<DialogueStatePacket> {
    override val handledPacketType: String = "dialogue_state"

    override fun handlePacket(packet: DialogueStatePacket) {
        println("🎭 Received dialogue state packet: npcId=${packet.npcId}, state=${packet.state}")

        // Future implementation would call:
        // when (packet.state) {
        //     DialogueState.START -> TypingManager.startSession(packet.npcId)
        //     DialogueState.END -> TypingManager.finishSessionForNpc(packet.npcId)
        //     else -> {}
        // }
    }
}
