package com.canefe.storyclient.client

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import kotlin.collections.plusAssign
import kotlin.math.max
import kotlin.text.iterator

object NPCDialogueHud {
    private var currentNpcName = ""
    private var fullTextRaw = ""              // raw text with '\n'
    private var displayedCharacters = 0
    private var ticksSinceLastUpdate = 0
    private var isTypingComplete = false
    private var dialogueVisible = false
    private var npcId: String? = null
    private var nameColor: String? = null
    private var autoCloseTimer = 0
    private var fadeState = FadeState.NONE
    private var fadeProgress = 0f
    private const val FADE_DURATION = 40 // ticks for fade transition
    private enum class FadeState { NONE, FADING_IN, FADING_OUT }

    private const val TYPING_SPEED = 10      // chars per tick
    private const val BOX_WIDTH = 250
    private const val BOX_HEIGHT = 50
    private const val MARGIN = 5
    private const val PADDING = 12            // Further reduced to give more text width

    init {
        HudRenderCallback.EVENT.register { ctx, _ ->
            if (dialogueVisible) {
                renderDialogueBox(ctx)
                advanceTyping()
            }
        }
    }

    fun startDialogue(npcId: String, text: String, color: String? = null) {
        this.npcId = npcId
        this.nameColor = color
        parseDialogue(text)
        dialogueVisible = true
        isTypingComplete = false
        displayedCharacters = 0
        autoCloseTimer = 0 // Reset timer
        fadeState = FadeState.FADING_IN
        fadeProgress = 0f
    }

    fun updateDialogue(npcId: String, text: String, color: String? = null) {
        if (npcId == this.npcId) {
            this.nameColor = color
            parseDialogue(text)
            isTypingComplete = false
            displayedCharacters = 0
            autoCloseTimer = 0 // Reset timer
        } else {
            startDialogue(npcId, text)
        }
    }

    fun endDialogue(npcIdToEnd: String) {

            // Set the timer to the threshold so it triggers fade-out on next tick
            autoCloseTimer = (StoryClientConfig.messageVanishTime * 20).toInt()
            // Start fade-out immediately
            fadeState = FadeState.FADING_OUT
            fadeProgress = 0f

    }

    private var npcAvatar: String? = null

    private fun parseDialogue(text: String) {
        val lines = text.lines()
        val nameIdx = lines.indexOfFirst { it.isNotBlank() }
        if (nameIdx >= 0 && nameIdx + 1 < lines.size) {
            val rawName = lines[nameIdx]

            // Check for non-alphanumeric characters at the beginning of name
            // which are likely to be the avatar/emoji
            val namePattern = Regex("([^\\p{L}\\p{N}\\s.,!?'-]+)(\\s*.*)")
            val match = namePattern.find(rawName)

            if (match != null && match.groups.size > 2) {
                // Extract the avatar
                npcAvatar = match.groups[1]?.value
                // Extract the actual name without formatting tags
                currentNpcName = match.groups[2]?.value
                    ?.replace(Regex("<[^>]+>"), "")  // Remove formatting tags
                    ?.trim() ?: ""
            } else {
                // No avatar found, just clean the name
                npcAvatar = null
                currentNpcName = rawName
                    .replace(Regex("<[^>]+>"), "")  // Remove formatting tags
                    .trim()
            }

            val body = lines
                .drop(nameIdx + 1)
                .takeWhile { it.isNotBlank() }
            fullTextRaw = body.joinToString("\n")
        }
    }

    private fun advanceTyping() {
        // Handle fade transitions
        when (fadeState) {
            FadeState.FADING_IN -> {
                fadeProgress += 1f / FADE_DURATION
                if (fadeProgress >= 1f) {
                    fadeProgress = 1f
                    fadeState = FadeState.NONE
                }
            }
            FadeState.FADING_OUT -> {
                fadeProgress += 1f / FADE_DURATION
                if (fadeProgress >= 1f) {
                    // Set dialogueVisible = false BEFORE changing fadeState
                    // to prevent a frame with full opacity
                    dialogueVisible = false
                    npcId = null
                    displayedCharacters = 0
                    isTypingComplete = false
                    fadeState = FadeState.NONE
                    fadeProgress = 1f
                    return
                }
            }
            else -> {}
        }

        if (isTypingComplete) {
            // Increment auto-close timer if typing is complete
            autoCloseTimer++
            if (autoCloseTimer >= StoryClientConfig.messageVanishTime * 20) {
                // Start fade-out after timer expires
                fadeState = FadeState.FADING_OUT
                fadeProgress = 0f
                autoCloseTimer = 0
            }
            return
        }

        ticksSinceLastUpdate++
        if (ticksSinceLastUpdate >= (20 / TYPING_SPEED)) {
            displayedCharacters++
            ticksSinceLastUpdate = 0
            if (displayedCharacters >= fullTextRaw.length) {
                isTypingComplete = true
                autoCloseTimer = 0 // Start the timer
            }
        }
    }

    private fun renderDialogueBox(ctx: DrawContext) {
        val opacity = when (fadeState) {
            FadeState.FADING_IN -> fadeProgress
            FadeState.FADING_OUT -> {
                // Prevent final frame flash by ensuring opacity is exactly 0 at the end
                if (fadeProgress >= 0.99f) 0f else 1f - fadeProgress
            }
            FadeState.NONE -> 1f
        }

        // Skip rendering entirely if opacity is 0
        if (opacity <= 0f) return

        val client = MinecraftClient.getInstance()
        val scale = StoryClientConfig.dialogueScale.toFloat()

        // Apply scaling transformation
        val matrices = ctx.matrices
        matrices.push()

        // Scale around the center of the dialogue box area
        val sw = client.window.scaledWidth
        val sh = client.window.scaledHeight
        val centerX = sw / 2f
        val centerY = sh - 130f // Approximate center of dialogue area

        matrices.translate(centerX, centerY, 0f)
        matrices.scale(scale, scale, 1f)
        matrices.translate(-centerX, -centerY, 0f)

        // Apply opacity to all colors
        val bgAlpha = (0xFF * opacity).toInt()
        val textAlpha = (0xFF * opacity).toInt()
        val borderAlpha = (0xE4934C * opacity).toInt()
        val avatarAlpha = (0xFF * opacity).toInt()

        // Convert to ARGB format
        val bgColor = (bgAlpha shl 24) or 0xfbc170
        val borderColor = (textAlpha shl 24) or 0xB86A2F
        val textBaseColor = (textAlpha shl 24) or 0x52130A
        val avatarColor = (avatarAlpha shl 24) or 0xFFFFFF

        val textRenderer = client.textRenderer

        // how much padding inside the box
        // val PADDING = 12

        // 1) pull the raw string (full or partial)
        val visibleRaw = fullTextRaw


        // 2) Strip leading spaces on each line
        val cleanedRaw = visibleRaw
            .lineSequence()
            .map { it.trimStart() }
            .joinToString(" ")

        // 2) tokenize on '*' to figure out which bits go italic+gray
        val tokens = mutableListOf<Pair<String, Boolean>>()  // (text, isItalic)
        var buffer = StringBuilder()
        var italicMode = false
        for (ch in cleanedRaw) {
            if (ch == '*') {
                // flush whatever we have so far
                tokens += buffer.toString() to italicMode
                buffer = StringBuilder()
                // flip italic mode, but don't include the '*'
                italicMode = !italicMode
            } else {
                buffer.append(ch)
            }
        }
        // final flush
        tokens += buffer.toString() to italicMode

        // 3) build one big styled Text from those tokens
        var formatted = Text.empty()
        for (i in tokens.indices) {
            val (piece, isIt) = tokens[i]
            if (piece.isEmpty()) continue
            if (isIt) {
                // wrap in parentheses if the user didn't already
                val core = piece.trim()
                val inside = if (core.startsWith("(") && core.endsWith(")")) core else "($core)"
                formatted = formatted.append(
                    Text.literal(inside)
                        .styled { s -> s.withItalic(true).withColor(Formatting.DARK_GRAY) }
                )
                // Add a newline after the action to match server formatting
                formatted = formatted.append(Text.literal("\n"))
            } else {
                // If this is the piece right after an action, trim leading spaces for proper alignmentso
                val isAfterAction = i > 0 && tokens[i - 1].second
                val processedPiece = if (isAfterAction) piece.trimStart() else piece
                formatted = formatted.append(Text.literal(processedPiece))
            }
        }

        // 3) wrap it to lines
        val maxTextWidth = BOX_WIDTH - PADDING * 2
        // temporary cap so wrapLines never goes off-screen
        val wrappedLines = textRenderer.wrapLines(formatted, maxTextWidth)


        // 4) measure widest line
        val widest = wrappedLines.maxOfOrNull { textRenderer.getWidth(it) } ?: 0

        // 5) build our box to just fit text + padding
        val boxWidth = max(BOX_WIDTH, widest + PADDING * 2)
        val lineHeight = textRenderer.fontHeight
        val linesCount = wrappedLines.size

        val boxHeight = max(BOX_HEIGHT,  PADDING * 2 + (linesCount * lineHeight))
        val x = (sw - boxWidth) / 2
        val y = sh - boxHeight - StoryClientConfig.dialogueYOffset

// 6) draw background & border
// Main background
        ctx.fill(x, y, x + boxWidth, y + boxHeight, bgColor)

// First draw the main borders
        ctx.fill(x, y, x + boxWidth, y + 3, borderColor)               // Top
        ctx.fill(x, y + boxHeight - 3, x + boxWidth, y + boxHeight, borderColor) // Bottom
        ctx.fill(x, y, x + 3, y + boxHeight, borderColor)               // Left
        ctx.fill(x + boxWidth - 3, y, x + boxWidth, y + boxHeight, borderColor)   // Right

// Decorative inner corners
        val cornerSize = 7  // Size of the inner corner decorations
// Top left corner
        ctx.fill(x + 3, y + 3, x + cornerSize, y + 5, borderColor)
        ctx.fill(x + 3, y + 3, x + 5, y + cornerSize, borderColor)

// Top right corner
        ctx.fill(x + boxWidth - cornerSize, y + 3, x + boxWidth - 3, y + 5, borderColor)
        ctx.fill(x + boxWidth - 5, y + 3, x + boxWidth - 3, y + cornerSize, borderColor)

// Bottom left corner
        ctx.fill(x + 3, y + boxHeight - 5, x + cornerSize, y + boxHeight - 3, borderColor)
        ctx.fill(x + 3, y + boxHeight - cornerSize, x + 5, y + boxHeight - 3, borderColor)

// Bottom right corner
        ctx.fill(x + boxWidth - cornerSize, y + boxHeight - 5, x + boxWidth - 3, y + boxHeight - 3, borderColor)
        ctx.fill(x + boxWidth - 5, y + boxHeight - cornerSize, x + boxWidth - 3, y + boxHeight - 3, borderColor)

// 7) draw NPC name with background
        val nameText = Text.literal(currentNpcName).styled { it.withBold(true) }
        val nameWidth = textRenderer.getWidth(nameText)
        val nameX = x
        val nameY = y - textRenderer.fontHeight - 4 // 4px vertical gap above box
        val nameColorValue = nameColor?.let { if (it.startsWith("#")) it else "#$it" } ?: "#FFCC44"
        val colorRgb = nameColorValue.removePrefix("#").trim().toInt(16)
        val colorWithAlpha = (textAlpha shl 24) or colorRgb

        ctx.drawText(textRenderer, nameText, x, y - 10, colorWithAlpha, false)

        if (npcAvatar != null) {
            val avatarText = Text.literal(npcAvatar)
            val avatarWidth = textRenderer.getWidth(avatarText)
            val avatarHeight = 42

            // Position calculations
            val avatarX = x + 2
            val avatarY = y - avatarHeight - 20

            // Frame dimensions (slightly larger than the avatar)
            //val frameX = avatarX - 4
            //val frameY = avatarY - 4
            //val frameWidth = avatarWidth + 8
            //val frameHeight = avatarHeight + 8

            val frameX = avatarX - 2
            val frameY = avatarY - 4
            val frameWidth = avatarWidth + 4
            val frameHeight = avatarHeight + 8
            // Draw medieval-style frame
            // Outer border
            //ctx.fill(frameX, frameY, frameX + frameWidth, frameY + frameHeight, colorWithAlpha)
            // Inner background
            //ctx.fill(frameX + 2, frameY + 2, frameX + frameWidth - 2, frameY + frameHeight - 2, bgColor)

            // Add decorative corners
            val cornerColor = colorWithAlpha

            // Top left corner
            ctx.fill(frameX, frameY, frameX + 2, frameY + 2, cornerColor)
            ctx.fill(frameX + 2, frameY, frameX + 4, frameY + 1, cornerColor)
            ctx.fill(frameX, frameY + 2, frameX + 1, frameY + 4, cornerColor)

            // Top right corner
            ctx.fill(frameX + frameWidth - 2, frameY, frameX + frameWidth, frameY + 2, cornerColor)
            ctx.fill(frameX + frameWidth - 4, frameY, frameX + frameWidth - 2, frameY + 1, cornerColor)
            ctx.fill(frameX + frameWidth - 1, frameY + 2, frameX + frameWidth, frameY + 4, cornerColor)

            // Bottom left corner
            ctx.fill(frameX, frameY + frameHeight - 2, frameX + 2, frameY + frameHeight, cornerColor)
            ctx.fill(frameX, frameY + frameHeight - 4, frameX + 1, frameY + frameHeight - 2, cornerColor)
            ctx.fill(frameX + 2, frameY + frameHeight - 1, frameX + 4, frameY + frameHeight, cornerColor)

            // Bottom right corner
            ctx.fill(frameX + frameWidth - 2, frameY + frameHeight - 2, frameX + frameWidth, frameY + frameHeight, cornerColor)
            ctx.fill(frameX + frameWidth - 1, frameY + frameHeight - 4, frameX + frameWidth, frameY + frameHeight - 2, cornerColor)
            ctx.fill(frameX + frameWidth - 4, frameY + frameHeight - 1, frameX + frameWidth - 2, frameY + frameHeight, cornerColor)

            // Draw the avatar text in the center of the frame
            ctx.drawText(textRenderer, avatarText, avatarX, avatarY, avatarColor, false)
        }

        // 8) draw each wrapped line, flush-left
        var textY = y + PADDING
        val textX = x + PADDING
        wrappedLines.forEach { line ->
            if (textY > y + boxHeight - PADDING) return@forEach
            ctx.drawText(textRenderer, line, textX, textY, textBaseColor, false)
            textY += lineHeight
        }

        // 9) blinking arrow
        if (isTypingComplete && (client.world?.time ?: 0) % 20 < 10) {
            val arrow = Text.literal("▼")
            val ax = x + boxWidth - PADDING - textRenderer.getWidth(arrow)
            val ay = y + boxHeight - PADDING - lineHeight
            ctx.drawText(textRenderer, arrow, ax, ay, textBaseColor, false)
        }

        // Restore the matrix transformation
        matrices.pop()
    }


    fun isVisible() = dialogueVisible
}
