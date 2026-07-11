package com.canefe.storyclient.client.confrontation

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext

/**
 * Choices-only overlay for a LOCKED confrontation. Deliberately NO full-screen
 * dim/panel — it draws only the prompt, the numbered option rows, and (when
 * allowed) the free-text line, low over the live cinematic view. Text uses an
 * outlined draw so it stays legible against the 3D scene.
 *
 * Rendered from HudRenderCallback while [ConfrontationState.active].
 */
object ConfrontationOverlay {

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val SHADOW = 0xFF101010.toInt()
    private const val DIM = 0xFFB0B0B0.toInt()

    /** Current free-text buffer (mirrors DecisionHud's freeform buffer). */
    var freeformInput: String = ""
        private set

    var freeformMode: Boolean = false
        private set

    fun render(ctx: DrawContext) {
        if (!ConfrontationState.active) return
        val mc = MinecraftClient.getInstance()
        val font = mc.textRenderer
        val sw = ctx.scaledWindowWidth
        val sh = ctx.scaledWindowHeight

        // Prompt sits above the option rows.
        val prompt = ConfrontationState.prompt
        var y = sh - 20 - (ConfrontationState.choices.size + 2) * 12
        if (prompt != null) {
            outlined(ctx, font, prompt, 16, y, WHITE)
            y += 16
        }

        if (ConfrontationState.myTurn) {
            ConfrontationState.choices.forEachIndexed { i, choice ->
                val dc = choice.dc?.let { " (DC $it)" } ?: ""
                outlined(ctx, font, "[${i + 1}] ${choice.label}$dc", 16, y, WHITE)
                y += 12
            }
            if (ConfrontationState.allowFreeText) {
                val caret = if (freeformMode) "|" else ""
                val text = if (freeformMode) "> $freeformInput$caret" else "[T] say something…"
                outlined(ctx, font, text, 16, y, DIM)
            }
        } else {
            outlined(ctx, font, "…waiting…", 16, y, DIM)
        }
    }

    private fun outlined(
        ctx: DrawContext,
        font: net.minecraft.client.font.TextRenderer,
        text: String,
        x: Int,
        y: Int,
        color: Int,
    ) {
        ctx.drawText(font, text, x + 1, y + 1, SHADOW, false)
        ctx.drawText(font, text, x, y, color, false)
    }

    // ── free-text buffer (driven by ConfrontationFreeformScreen) ─────────────

    fun openFreeform() {
        if (!ConfrontationState.allowFreeText) return
        freeformMode = true
        freeformInput = ""
    }

    fun appendFreeformChar(c: Char) {
        if (freeformMode) freeformInput += c
    }

    fun backspaceFreeform() {
        if (freeformMode && freeformInput.isNotEmpty()) {
            freeformInput = freeformInput.dropLast(1)
        }
    }

    fun cancelFreeform() {
        freeformMode = false
        freeformInput = ""
    }

    fun submitFreeform() {
        val id = ConfrontationState.confrontationId
        val text = freeformInput.trim()
        freeformMode = false
        freeformInput = ""
        if (id != null && text.isNotEmpty()) {
            ConfrontationPacketReceiver.sendFreeText(id, text)
            ConfrontationState.clearMyTurn()
        }
    }

    /** Pick option [index] (0-based) if it's our turn and it exists. */
    fun pick(index: Int) {
        if (!ConfrontationState.myTurn) return
        val choice = ConfrontationState.choices.getOrNull(index) ?: return
        val id = ConfrontationState.confrontationId ?: return
        ConfrontationPacketReceiver.sendPick(id, choice.id)
        ConfrontationState.clearMyTurn()
    }
}
