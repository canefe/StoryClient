package com.canefe.storyclient.client

import com.canefe.storyclient.client.mixin.ChatHudAccessor
import net.kyori.adventure.platform.fabric.impl.client.ClientAudience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import java.util.ConcurrentModificationException
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.hud.ChatHudLine
import net.minecraft.text.Text
import kotlin.collections.iterator
import kotlin.text.contains

object TypingManager {
    private val activeSessions = java.util.concurrent.ConcurrentHashMap<String, TypingSession>()
    private val cleanupTimeout = 10000L // 10 seconds for complete cleanup
    private val sessionLastSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val npcMessages = java.util.concurrent.ConcurrentHashMap<String, String>()

    // Voice sync: holds dialogue display until voice arrives
    private data class PendingDialogue(
        val npcId: String,
        val text: String,
        val color: String?,
        val isNew: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val pendingVoiceDialogues = java.util.concurrent.ConcurrentHashMap<String, PendingDialogue>()
    private const val VOICE_WAIT_TIMEOUT_MS = 3000L // Display dialogue after 3s even without voice

    fun hasActiveSession(): Boolean = activeSessions.isNotEmpty()

    internal fun isSessionActive(npcId: String): Boolean = activeSessions.containsKey(npcId)

    fun getActiveSessionText(): String? {
        return activeSessions.values.firstOrNull()?.getCurrentText()
    }

    fun getActiveSession(): TypingSession? = activeSessions.values.firstOrNull()

    fun getActiveNpcUuid(): String? = activeSessions.keys.firstOrNull()

    private fun parseAndDisplayNpcMessage(npcId: String, text: String, color: String? = null, voicePending: Boolean = false) {
        // The pacer owns voice-wait state via Bundle.voicePending. We hand
        // it the chunk and let it decide when to call back into renderNpcMessage.
        com.canefe.storyclient.client.pacing.NpcEventPacer.onDialogueChunk(npcId, text, color, voicePending)
    }

    internal fun renderNpcMessage(npcId: String, text: String, color: String?, isNew: Boolean) {
        MinecraftClient.getInstance().execute {
            val entityId = findNpcEntityId(npcId)

            if (isNew) {
                if (StoryClientConfig.useBubbleRenderer) {
                    BubbleRenderer.startBubble(npcId, entityId, text, color)
                } else {
                    NPCDialogueHud.startDialogue(npcId, text, color)
                }
            } else {
                if (StoryClientConfig.useBubbleRenderer) {
                    BubbleRenderer.updateBubble(npcId, text, color)
                } else {
                    NPCDialogueHud.updateDialogue(npcId, text, color)
                }
            }
        }
    }

    /**
     * Called when audio arrives for an NPC. If there's a pending dialogue waiting
     * for voice, display it now.
     */
    fun onVoiceReceived(npcId: String) {
        val pending = pendingVoiceDialogues.remove(npcId) ?: return
        renderNpcMessage(pending.npcId, pending.text, pending.color, pending.isNew)
    }

    internal fun findNpcEntityId(npcId: String): Int? {
        return try {
            val client = MinecraftClient.getInstance()
            val player = client.player ?: return null
            val world = client.world ?: return null

            val searchBox = net.minecraft.util.math.Box.of(
                player.pos,
                64.0, 64.0, 64.0
            )

            world.getOtherEntities(null, searchBox) {
                it is net.minecraft.entity.LivingEntity &&
                !it.isPlayer &&
                it.uuidAsString == npcId
            }.firstOrNull()?.id
        } catch (_: Exception) {
            null
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
                // Format: color:#123456 voice:1 id:uuid:message
                // or:     color:#123456 id:uuid:message (no voice flag)
                val colorEndIndex = tagContent.indexOf(" id:")
                if (colorEndIndex > 0) {
                    val headerSection = tagContent.substring(6, colorEndIndex) // e.g. "#CC7B3D voice:1" or "#CC7B3D"
                    val voicePending = headerSection.contains("voice:1")
                    val color = headerSection.replace(" voice:1", "").trim()

                    val remainingContent = tagContent.substring(colorEndIndex + 4) // Get content after " id:"

                    // Split to get NPC ID and message content
                    val parts = remainingContent.split(":", limit = 2)
                    if (parts.size == 2) {
                        val npcId = parts[0]
                        val newText = parts[1]

                        val now = System.currentTimeMillis()
                        sessionLastSeen[npcId] = now

                        // Pass color and voice flag to the dialogue system
                        parseAndDisplayNpcMessage(npcId, newText, color, voicePending)

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
            println("DEBUG TypingManager: Processing typing end message")
            // Parse npcId from the message if available (format: "<npc_typing_end>id:uuid")
            val endContent = rawText.substringAfter("<npc_typing_end>")
            println("DEBUG TypingManager: End content = '$endContent'")

            if (endContent.startsWith("id:")) {
                // Extract specific NPC ID
                val npcId = endContent.substringAfter("id:").substringBefore("<").trim()
                println("DEBUG TypingManager: Extracted npcId = '$npcId'")
                if (npcId.isNotEmpty()) {
                    // End specific session
                    println("DEBUG TypingManager: Calling finishSessionForNpc for $npcId")
                    finishSessionForNpc(npcId)
                } else {
                    // Fallback: end all sessions
                    println("DEBUG TypingManager: Empty npcId, finishing all sessions")
                    finishAllSessions()
                }
            } else {
                // Old format or no ID: end all sessions
                println("DEBUG TypingManager: No id: prefix, finishing all sessions")
                finishAllSessions()
            }
        }
    }

    private fun updateOrAddChatMessage(npcId: String, text: String) {
        val client = MinecraftClient.getInstance() ?: return
        val chatHud = client.inGameHud?.chatHud ?: return
        val accessor = chatHud as ChatHudAccessor

        // Parse the message
        val parsedText = parseMiniMessage(text)

        client.execute {
            if (!npcMessages.containsKey(npcId)) {
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
        println("DEBUG TypingManager: finishSessionForNpc called for $npcId")
        println("DEBUG TypingManager: useBubbleRenderer = ${StoryClientConfig.useBubbleRenderer}")
        activeSessions[npcId]?.markDone()
        if (StoryClientConfig.useBubbleRenderer) {
            println("DEBUG TypingManager: Calling BubbleRenderer.endBubble")
            BubbleRenderer.endBubble(npcId)
        } else {
            println("DEBUG TypingManager: Calling NPCDialogueHud.endDialogue")
            NPCDialogueHud.endDialogue(npcId)
        }
    }

    fun finishAllSessions() {
        val keys = activeSessions.keys.toList()
        for (npcId in keys) {
            finishSessionForNpc(npcId)
        }
        activeSessions.clear()
    }

    fun tick() {
        val now = System.currentTimeMillis()
        val finishedSessions = mutableListOf<String>()

        // Check active sessions (snapshot to avoid ConcurrentModificationException)
        for ((npcId, session) in activeSessions.toMap()) {
            session.tick()

            if (session.isComplete()) {
                finishedSessions.add(npcId)
            }
        }

        // Clean up completed sessions
        for (npcId in finishedSessions) {
            activeSessions.remove(npcId)
        }

        // Clean up sessions that haven't been seen in a while (snapshot)
        val outdatedSessions = sessionLastSeen.entries.toList()
            .filter { (npcId, lastSeen) ->
                now - lastSeen > cleanupTimeout && activeSessions.containsKey(npcId)
            }
            .map { it.key }

        for (npcId in outdatedSessions) {
            finishSessionForNpc(npcId)
            sessionLastSeen.remove(npcId)
        }

        // Timeout pending voice dialogues — display them even without voice
        val timedOut = pendingVoiceDialogues.entries.toList()
            .filter { (_, pending) -> now - pending.timestamp > VOICE_WAIT_TIMEOUT_MS }
        for ((npcId, pending) in timedOut) {
            pendingVoiceDialogues.remove(npcId)
            renderNpcMessage(pending.npcId, pending.text, pending.color, pending.isNew)
        }

        com.canefe.storyclient.client.pacing.NpcEventPacer.tick()
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


