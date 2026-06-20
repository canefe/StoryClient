package com.canefe.storyclient.client.pause

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

/**
 * Draws a faded black overlay with a centered "STORY PAUSED" label while the
 * simulation is paused ([PauseState.paused]). The overlay alpha eases in/out so
 * the freeze doesn't snap on/off jarringly.
 *
 * Rendered from the HudRenderCallback chain in NPCMessageParserClient.
 */
object PauseOverlayHud {
    private const val MAX_DIM_ALPHA = 0.55f
    private const val FADE_PER_FRAME = 0.08f

    private var alpha = 0f

    fun render(ctx: DrawContext) {
        val target = if (PauseState.paused) 1f else 0f
        alpha += (target - alpha).coerceIn(-FADE_PER_FRAME, FADE_PER_FRAME)
        if (alpha <= 0.001f) {
            alpha = 0f
            return
        }

        val client = MinecraftClient.getInstance()
        // NOTE: intentionally drawn even when a Screen (chat, pause menu) is
        // open — HudRenderCallback still fires underneath those, and the freeze
        // should stay visible the whole time the sim is paused.

        val w = ctx.scaledWindowWidth
        val h = ctx.scaledWindowHeight

        // Faded black scrim.
        val dim = (MAX_DIM_ALPHA * alpha * 255f).toInt().coerceIn(0, 255)
        ctx.fill(0, 0, w, h, dim shl 24)

        val textRenderer = client.textRenderer
        val textAlpha = (alpha * 255f).toInt().coerceIn(0, 255)
        if (textAlpha <= 4) return

        val title = Text.literal("STORY PAUSED")
        val titleColor = (textAlpha shl 24) or 0xFFFFFF
        val tw = textRenderer.getWidth(title)
        val scale = 2.0f
        ctx.matrices.push()
        ctx.matrices.translate((w / 2).toFloat(), (h / 2 - 12).toFloat(), 0f)
        ctx.matrices.scale(scale, scale, 1f)
        ctx.drawText(textRenderer, title, -(tw / 2), -(textRenderer.fontHeight / 2), titleColor, true)
        ctx.matrices.pop()

        val sub = Text.literal("The world is frozen")
        val subColor = (textAlpha shl 24) or 0xBBBBBB
        val sw = textRenderer.getWidth(sub)
        ctx.drawText(textRenderer, sub, w / 2 - sw / 2, h / 2 + 12, subColor, true)
    }
}
