package com.canefe.storyclient.client.packets

import java.util.concurrent.ConcurrentHashMap

/**
 * Central manager for Story ecosystem packets.
 * Handles packet routing, handler registration, and error management.
 *
 * This system is designed to eventually replace the message-based
 * <npc_typing> system and abstract the audio packet system into
 * a unified packet handling architecture.
 *
 * Usage:
 * ```
 * // Register handlers
 * StoryPacketManager.registerHandler(NPCTypingPacketHandler())
 * StoryPacketManager.registerHandler(NPCAudioPacketHandler())
 *
 * // Receive packets
 * StoryPacketManager.receivePacket(typingPacket)
 * StoryPacketManager.receivePacket(audioPacket)
 * ```
 */
object StoryPacketManager {
    private val handlers = ConcurrentHashMap<String, StoryPacketHandler<*>>()
    private val statisticsEnabled = true
    private val packetStats = ConcurrentHashMap<String, PacketStatistics>()

    /**
     * Statistics for monitoring packet flow.
     */
    data class PacketStatistics(
        var received: Long = 0,
        var handled: Long = 0,
        var errors: Long = 0,
        var lastReceived: Long = 0
    )

    /**
     * Initialize the packet manager with default handlers.
     * Call this during mod initialization.
     */
    fun initialize() {
        println("🔧 Initializing Story Packet Manager...")

        // Register default handlers
        registerHandler(NPCTypingPacketHandler())
        registerHandler(NPCAudioPacketHandler())
        registerHandler(NPCMetadataPacketHandler())
        registerHandler(DialogueStatePacketHandler())

        println("✅ Story Packet Manager initialized with ${handlers.size} handlers")
    }

    /**
     * Register a packet handler for a specific packet type.
     * @param handler The handler to register
     */
    fun <T : StoryPacket> registerHandler(handler: StoryPacketHandler<T>) {
        val packetType = handler.handledPacketType

        if (handlers.containsKey(packetType)) {
            println("⚠️ Overwriting existing handler for packet type: $packetType")
        }

        handlers[packetType] = handler
        println("📋 Registered handler for packet type: $packetType")
    }

    /**
     * Unregister a packet handler.
     * @param packetType The packet type to unregister
     */
    fun unregisterHandler(packetType: String) {
        handlers.remove(packetType)
        println("🗑️ Unregistered handler for packet type: $packetType")
    }

    /**
     * Receive and route a packet to the appropriate handler.
     * @param packet The packet to handle
     */
    fun <T : StoryPacket> receivePacket(packet: T) {
        val packetType = packet.packetType

        // Update statistics
        if (statisticsEnabled) {
            val stats = packetStats.getOrPut(packetType) { PacketStatistics() }
            stats.received++
            stats.lastReceived = System.currentTimeMillis()
        }

        // Find handler
        val handler = handlers[packetType]

        if (handler == null) {
            println("⚠️ No handler registered for packet type: $packetType")
            return
        }

        // Route packet to handler
        try {
            @Suppress("UNCHECKED_CAST")
            (handler as StoryPacketHandler<T>).handlePacket(packet)

            // Update statistics
            if (statisticsEnabled) {
                packetStats[packetType]?.handled++
            }
        } catch (e: Exception) {
            println("❌ Error handling packet of type $packetType: ${e.message}")
            e.printStackTrace()

            // Update statistics
            if (statisticsEnabled) {
                packetStats[packetType]?.errors++
            }

            // Call error handler
            try {
                @Suppress("UNCHECKED_CAST")
                (handler as StoryPacketHandler<T>).onError(packet, e)
            } catch (errorHandlingException: Exception) {
                println("❌ Error in error handler: ${errorHandlingException.message}")
            }
        }
    }

    /**
     * Check if a handler is registered for a packet type.
     * @param packetType The packet type to check
     * @return true if a handler is registered
     */
    fun hasHandler(packetType: String): Boolean {
        return handlers.containsKey(packetType)
    }

    /**
     * Get all registered packet types.
     * @return Set of registered packet type identifiers
     */
    fun getRegisteredPacketTypes(): Set<String> {
        return handlers.keys.toSet()
    }

    /**
     * Get statistics for a specific packet type.
     * @param packetType The packet type to get statistics for
     * @return Statistics or null if not available
     */
    fun getStatistics(packetType: String): PacketStatistics? {
        return packetStats[packetType]
    }

    /**
     * Get all packet statistics.
     * @return Map of packet type to statistics
     */
    fun getAllStatistics(): Map<String, PacketStatistics> {
        return packetStats.toMap()
    }

    /**
     * Print current statistics to console.
     * Useful for debugging and monitoring.
     */
    fun printStatistics() {
        println("📊 Story Packet Manager Statistics:")
        println("=" .repeat(50))

        if (packetStats.isEmpty()) {
            println("No packets received yet.")
            return
        }

        packetStats.forEach { (packetType, stats) ->
            val successRate = if (stats.received > 0) {
                ((stats.handled.toDouble() / stats.received.toDouble()) * 100).toInt()
            } else {
                0
            }

            println("  $packetType:")
            println("    Received: ${stats.received}")
            println("    Handled:  ${stats.handled} ($successRate%)")
            println("    Errors:   ${stats.errors}")
            println("    Last:     ${stats.lastReceived}")
        }

        println("=" .repeat(50))
    }

    /**
     * Clear all statistics.
     * Useful for testing or resetting metrics.
     */
    fun clearStatistics() {
        packetStats.clear()
        println("🗑️ Cleared all packet statistics")
    }

    /**
     * Shutdown the packet manager and cleanup resources.
     * Call this during mod shutdown.
     */
    fun shutdown() {
        println("🛑 Shutting down Story Packet Manager...")
        handlers.clear()
        packetStats.clear()
        println("✅ Story Packet Manager shutdown complete")
    }
}
