package com.canefe.storyclient.client.puppet

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext

/**
 * Top-center HUD overlay showing the current puppet group, plus a tinted
 * crosshair indicator. Renders nothing when not in puppet mode.
 */
object PuppetHud {
    private const val MAX_NAMES_SHOWN = 3

    fun render(ctx: DrawContext) {
        if (!PuppetState.inPuppetMode) return
        val client = MinecraftClient.getInstance()
        val font = client.textRenderer
        val w = client.window.scaledWidth

        val names = PuppetState.groupNames
        val displayed = names.take(MAX_NAMES_SHOWN).joinToString(", ")
        val overflow = names.size - MAX_NAMES_SHOWN
        val text =
            if (overflow > 0) "Puppeting: $displayed, +$overflow more"
            else "Puppeting: $displayed"

        val textWidth = font.getWidth(text)
        val x = (w - textWidth) / 2
        val y = 6
        // Background
        ctx.fill(x - 4, y - 2, x + textWidth + 4, y + font.fontHeight + 2, 0xCC222222.toInt())
        ctx.drawTextWithShadow(font, text, x, y, 0xFFCC88FF.toInt())

        // Tinted crosshair: small purple square overlay at center
        val cx = w / 2
        val cy = client.window.scaledHeight / 2
        ctx.fill(cx - 5, cy - 1, cx + 5, cy + 1, 0x99AA66FF.toInt())
        ctx.fill(cx - 1, cy - 5, cx + 1, cy + 5, 0x99AA66FF.toInt())
    }
}
