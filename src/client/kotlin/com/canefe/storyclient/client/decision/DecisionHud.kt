package com.canefe.storyclient.client.decision

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.StringVisitable
import net.minecraft.text.Text

object DecisionHud {

    private var selectedIndex: Int = -1

    fun render(ctx: DrawContext) {
        if (!DecisionState.isVisible) return

        val client = MinecraftClient.getInstance()
        val sw = client.window.scaledWidth
        val sh = client.window.scaledHeight

        DecisionState.activePrompt?.let { prompt ->
            if (DecisionState.isCritical) {
                ctx.fill(0, 0, sw, sh, 0xCC000000.toInt())
            }
            renderDecisionPanel(ctx, prompt, sw, sh)
        }

        DecisionState.activeObserve?.let { observe ->
            renderObserveStrip(ctx, observe, sw, sh)
        }
    }

    private fun renderDecisionPanel(ctx: DrawContext, prompt: DecisionPrompt, sw: Int, sh: Int) {
        val client = MinecraftClient.getInstance()
        val font = client.textRenderer

        val panelW = (sw * 0.6f).toInt().coerceAtMost(520)
        val panelX = (sw - panelW) / 2
        var panelY = (sh * 0.15f).toInt()

        // Panel background
        ctx.fill(panelX - 9, panelY - 9, panelX + panelW + 9, sh - 39, 0xFF444444.toInt())
        ctx.fill(panelX - 8, panelY - 8, panelX + panelW + 8, sh - 40, 0xDD111111.toInt())

        // Countdown timer (top-right of panel)
        val secondsLeft = (DecisionState.ticksRemaining / 20).coerceAtLeast(0)
        val timerColor = when {
            secondsLeft > 30 -> 0xFFFFFFFF.toInt()
            secondsLeft > 10 -> 0xFFFFAA00.toInt()
            else -> 0xFFFF4444.toInt()
        }
        val timerText = "%02d:%02d".format(secondsLeft / 60, secondsLeft % 60)
        ctx.drawTextWithShadow(font, timerText, panelX + panelW - font.getWidth(timerText), panelY, timerColor)

        // Title
        ctx.drawTextWithShadow(font, Text.literal(prompt.title).styled { it.withBold(true) }, panelX, panelY, 0xFFFFEEAA.toInt())
        panelY += font.fontHeight + 6

        // Context — wrapped lines
        val contextLines = font.wrapLines(Text.literal(prompt.context) as StringVisitable, panelW)
        contextLines.forEach { line ->
            ctx.drawText(font, line, panelX, panelY, 0xFFCCCCCC.toInt(), true)
            panelY += font.fontHeight + 2
        }
        panelY += 10

        // NPC voice cards row
        if (prompt.npcVoices.isNotEmpty()) {
            val voiceW = (panelW / prompt.npcVoices.size) - 4
            prompt.npcVoices.forEachIndexed { i, voice ->
                val vx = panelX + i * (voiceW + 4)
                val isHighlighted = i == DecisionState.highlightedVoiceIndex
                val bgColor = if (isHighlighted) 0xFF223344.toInt() else 0xFF1A1A1A.toInt()
                val borderColor = if (isHighlighted) 0xFF4488CC.toInt() else 0xFF333333.toInt()

                ctx.fill(vx - 1, panelY - 1, vx + voiceW + 1, panelY + 53, borderColor)
                ctx.fill(vx, panelY, vx + voiceW, panelY + 52, bgColor)

                val stanceColor = when (voice.stance) {
                    "cautious" -> 0xFFAACC44.toInt()
                    "aggressive" -> 0xFFCC4444.toInt()
                    else -> 0xFFAAAAAA.toInt()
                }
                ctx.drawTextWithShadow(font, Text.literal(voice.name).styled { it.withBold(true) }, vx + 4, panelY + 4, stanceColor)
                val opinionLines = font.wrapLines(Text.literal(voice.opinion) as StringVisitable, voiceW - 8)
                opinionLines.take(2).forEachIndexed { li, line ->
                    ctx.drawText(font, line, vx + 4, panelY + 4 + font.fontHeight + 2 + li * (font.fontHeight + 1), 0xFFBBBBBB.toInt(), true)
                }
            }
            panelY += 62
        }

        // "— Choose —" label
        val chooseLabel = "— Choose —"
        ctx.drawTextWithShadow(font, chooseLabel, panelX + (panelW - font.getWidth(chooseLabel)) / 2, panelY, 0xFF888888.toInt())
        panelY += font.fontHeight + 6

        // Options
        prompt.options.forEachIndexed { i, option ->
            val isSelected = selectedIndex == i
            val optBg = if (isSelected) 0xFF1E3A5A.toInt() else 0xFF1A1A2A.toInt()
            val optBorder = if (isSelected) 0xFF5599DD.toInt() else 0xFF333355.toInt()
            ctx.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + font.fontHeight + 7, optBorder)
            ctx.fill(panelX, panelY, panelX + panelW, panelY + font.fontHeight + 6, optBg)

            val letter = ('A' + i).toString()
            ctx.drawTextWithShadow(font, "[$letter] ${option.label}", panelX + 6, panelY + 3, 0xFFEEEEEE.toInt())
            if (option.consequenceHint.isNotEmpty()) {
                val hintText = option.consequenceHint
                ctx.drawTextWithShadow(font, hintText, panelX + panelW - font.getWidth(hintText) - 6, panelY + 3, 0xFF888888.toInt())
            }
            panelY += font.fontHeight + 10
        }

        // Freeform option
        if (prompt.allowFreeform) {
            val isFreeformSelected = selectedIndex == prompt.options.size
            val fbg = if (isFreeformSelected || DecisionState.freeformMode) 0xFF1A2A1A.toInt() else 0xFF1A1A1A.toInt()
            val fborder = if (isFreeformSelected || DecisionState.freeformMode) 0xFF44AA44.toInt() else 0xFF333333.toInt()
            ctx.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + font.fontHeight + 7, fborder)
            ctx.fill(panelX, panelY, panelX + panelW, panelY + font.fontHeight + 6, fbg)

            val freeformDisplay = if (DecisionState.freeformMode)
                "> ${DecisionState.freeformInput}|"
            else
                "[+] Say something else..."
            ctx.drawTextWithShadow(font, freeformDisplay, panelX + 6, panelY + 3, 0xFF88CC88.toInt())
        }
    }

    private fun renderObserveStrip(ctx: DrawContext, observe: DecisionObserve, sw: Int, sh: Int) {
        val client = MinecraftClient.getInstance()
        val font = client.textRenderer
        val stripH = font.fontHeight + 10
        val y = sh - stripH - 10

        ctx.fill(0, y - 2, sw, y + stripH + 2, 0xCC111111.toInt())
        val msg = "${observe.leaderName} is making a decision..."
        ctx.drawTextWithShadow(font, msg, (sw - font.getWidth(msg)) / 2, y + 5, 0xFFCCCC88.toInt())
    }

    /** Returns true if the key was consumed. */
    fun handleKeyPress(keyCode: Int): Boolean {
        val prompt = DecisionState.activePrompt ?: return false
        println("[DecisionHud] handleKeyPress key=$keyCode freeform=${DecisionState.freeformMode} selected=$selectedIndex")

        if (DecisionState.freeformMode) {
            return handleFreeformKey(keyCode, prompt)
        }

        val optionCount = prompt.options.size + (if (prompt.allowFreeform) 1 else 0)
        if (optionCount == 0) return false

        return when (keyCode) {
            in 49..57 -> {
                val idx = keyCode - 49
                if (idx < prompt.options.size) {
                    submitChoice(prompt, prompt.options[idx].id)
                    true
                } else false
            }
            265 -> { // GLFW_KEY_UP
                selectedIndex = if (selectedIndex <= 0) optionCount - 1 else selectedIndex - 1
                true
            }
            264 -> { // GLFW_KEY_DOWN
                selectedIndex = if (selectedIndex < 0) 0 else (selectedIndex + 1) % optionCount
                true
            }
            257 -> { // GLFW_KEY_ENTER
                if (selectedIndex in prompt.options.indices) {
                    submitChoice(prompt, prompt.options[selectedIndex].id)
                    true
                } else if (prompt.allowFreeform && selectedIndex == prompt.options.size) {
                    DecisionState.freeformMode = true
                    true
                } else false
            }
            else -> false
        }
    }

    private fun handleFreeformKey(keyCode: Int, prompt: DecisionPrompt): Boolean {
        return when (keyCode) {
            259 -> { // GLFW_KEY_BACKSPACE
                if (DecisionState.freeformInput.isNotEmpty()) {
                    DecisionState.freeformInput = DecisionState.freeformInput.dropLast(1)
                }
                true
            }
            257 -> { // GLFW_KEY_ENTER
                val text = DecisionState.freeformInput.trim()
                if (text.isNotEmpty()) {
                    println("[DecisionHud] freeform submit decisionId=${prompt.decisionId} text='${text.take(40)}'")
                    DecisionPacketReceiver.sendResponse(prompt.decisionId, null, text)
                    selectedIndex = -1
                }
                true
            }
            256 -> { // GLFW_KEY_ESCAPE
                DecisionState.freeformMode = false
                DecisionState.freeformInput = ""
                true
            }
            else -> false
        }
    }

    private fun submitChoice(prompt: DecisionPrompt, choiceId: String) {
        println("[DecisionHud] submitChoice decisionId=${prompt.decisionId} choiceId=$choiceId")
        DecisionPacketReceiver.sendResponse(prompt.decisionId, choiceId, null)
        selectedIndex = -1
    }

    /** Append a character to freeform input (called from CharTyped event). */
    fun appendFreeformChar(char: Char) {
        if (!DecisionState.freeformMode) return
        if (char.isLetterOrDigit() || char == ' ' || char.code > 31) {
            DecisionState.freeformInput += char
        }
    }
}
