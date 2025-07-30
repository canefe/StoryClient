package com.canefe.storyclient.client

import com.canefe.storyclient.client.mixin.ChatHudAccessor
import net.kyori.adventure.platform.fabric.impl.client.ClientAudience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.hud.ChatHudLine
import net.minecraft.text.Text
import kotlin.collections.iterator
import kotlin.text.contains

object TypingManager {
    private val activeSessions: MutableMap<String, TypingSession> = mutableMapOf()
    private val cleanupTimeout = 10000L // 10 seconds for complete cleanup
    private val sessionLastSeen: MutableMap<String, Long> = mutableMapOf()
    private val npcMessages = mutableMapOf<String, String>()

    fun hasActiveSession(): Boolean = activeSessions.isNotEmpty()

    fun getActiveSessionText(): String? {
        return activeSessions.values.firstOrNull()?.getCurrentText()
    }

    fun getActiveSession(): TypingSession? = activeSessions.values.firstOrNull()


    private fun parseAndDisplayNpcMessage(npcId: String, text: String, color: String? = null) {
        if (npcId !in activeSessions) {
            NPCDialogueHud.startDialogue(npcId, text, color)
        } else {
            NPCDialogueHud.updateDialogue(npcId, text, color)
        }
    }

    fun onIncomingServerMessage(rawText: String) {
        if (!StoryClientConfig.modEnabled)
            return

        if (rawText.contains("<npc_typing>")) {
            // Extract content between <npc_typing> tags
            val tagContent = rawText.substringAfter("<npc_typing>").substringBefore("<npc_typing_end>")

            // Check if tagContent contains color information
            if (tagContent.startsWith("color:")) {
                // New format: color:#123456id:uuid:message
                val colorEndIndex = tagContent.indexOf("id:")
                if (colorEndIndex > 0) {
                    val color = tagContent.substring(6, colorEndIndex) // Extract color code
                    val remainingContent = tagContent.substring(colorEndIndex + 3) // Get content after "id:"

                    // Split to get NPC ID and message content
                    val parts = remainingContent.split(":", limit = 2)
                    if (parts.size == 2) {
                        val npcId = parts[0]
                        val newText = parts[1]

                        val now = System.currentTimeMillis()
                        sessionLastSeen[npcId] = now

                        // Pass color information to the dialogue system
                        parseAndDisplayNpcMessage(npcId, newText, color)

                        val session = activeSessions.getOrPut(npcId) {
                            TypingSession(newText)
                        }

                        session.updateText(newText)
                    }
                }
            } else {
                // Old format: uuid:message
                val parts = tagContent.split(":", limit = 2)
                if (parts.size == 2) {
                    val npcId = parts[0]
                    val newText = parts[1]

                    val now = System.currentTimeMillis()
                    sessionLastSeen[npcId] = now

                    parseAndDisplayNpcMessage(npcId, newText)

                    val session = activeSessions.getOrPut(npcId) {
                        TypingSession(newText)
                    }

                    session.updateText(newText)
                }
            }
        } else if (rawText.contains("<npc_typing_end>")) {
            // get npcId from id:xxx (example: "<npc_typing_end>id:12345")
            finishAllSessions()
            NPCDialogueHud.endDialogue("a")

        }
    }

    private fun updateOrAddChatMessage(npcId: String, text: String) {
        val client = MinecraftClient.getInstance() ?: return
        val chatHud = client.inGameHud?.chatHud ?: return
        val accessor = chatHud as ChatHudAccessor

        // Parse the message
        val parsedText = parseMiniMessage(text)

        client.execute {
            if (npcId !in npcMessages) {
                // First message for this NPC
                chatHud.addMessage(parsedText)
                npcMessages[npcId] = text
            } else {
                // Update existing message
                val messages = accessor.messages
                val visibleMessages = accessor.visibleMessages

                // Store new content
                npcMessages[npcId] = text

                // We need to update both visible and stored messages
                var updated = false

                // Look for the message to update - checking by npcId
                for (i in messages.indices.reversed()) {
                    // Since we can't directly compare content, use the most recent message
                    // that was added after we started tracking this npcId
                    if (!updated) {
                        messages[i] = ChatHudLine(
                            client.inGameHud.ticks,
                            parsedText,
                            null,
                            null
                        )
                        updated = true
                        break
                    }
                }

                // Update visible messages too
                if (updated) {
                    // Refresh the chat display
                    accessor.refreshChatMessages()
                }
            }
        }
    }

    private fun parseMiniMessage(input: String): Text {
        return try {
            val miniMessage = MiniMessage.builder().strict(false).build()
            val component: Component = miniMessage.deserialize(input.trimStart())
            val audience = ClientAudience(MinecraftClient.getInstance(), null)
            audience.controller().toNative(component)
        } catch (e: Exception) {
            println("Error parsing MiniMessage: ${e.message}")
            Text.literal(input)
        }
    }

    fun finishSessionForNpc(npcId: String) {
        activeSessions[npcId]?.markDone()
        NPCDialogueHud.endDialogue(npcId) // Add this line to trigger fade-out
    }

    fun finishAllSessions() {
        for (npcId in activeSessions.keys) {
            finishSessionForNpc(npcId)
        }
        activeSessions.clear()
    }

    fun tick() {
        val now = System.currentTimeMillis()
        val finishedSessions = mutableListOf<String>()

        // Check active sessions
        for ((npcId, session) in activeSessions) {
            session.tick()

            if (session.isComplete()) {
                finishedSessions.add(npcId)
            }
        }

        // Clean up completed sessions
        for (npcId in finishedSessions) {
            activeSessions.remove(npcId)
        }

        // Clean up sessions that haven't been seen in a while
        val outdatedSessions = sessionLastSeen.entries
            .filter { (npcId, lastSeen) ->
                now - lastSeen > cleanupTimeout && npcId in activeSessions
            }
            .map { it.key }

        for (npcId in outdatedSessions) {
            finishSessionForNpc(npcId)
            sessionLastSeen.remove(npcId)
        }
    }
}



class TypingSession(private var fullText: String) {
    private var lastUpdateTime: Long = System.currentTimeMillis()
    private var done = false
    private val inactivityTimeout = 5000L // 5 seconds timeout
    private val formattedLines = mutableListOf<String>()

    fun getCurrentText(): String = fullText

    // Returns all formatted lines for display
    fun getFormattedLines(): List<String> = formattedLines

    fun updateText(newText: String) {
        fullText = newText
        lastUpdateTime = System.currentTimeMillis()

        // Parse the message to extract formatted lines
        updateFormattedLines(newText)
    }

    private fun updateFormattedLines(text: String) {
        formattedLines.clear()

        // Split by literal newlines (don't use regex)
        val lines = text.split("\n")

        // Keep all lines, including empty ones for proper spacing
        for (line in lines) {
            formattedLines.add(line)
        }
    }

    fun markDone() {
        if (!done) {
            done = true
            // We don't need to add to chat since the server will send the final message
        }
    }

    fun tick() {
        if (done) return

        if (System.currentTimeMillis() - lastUpdateTime > inactivityTimeout) {
            done = true
        }
    }

    fun isComplete(): Boolean = done
}


