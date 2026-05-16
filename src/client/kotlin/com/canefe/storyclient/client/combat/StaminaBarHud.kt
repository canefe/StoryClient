package com.canefe.storyclient.client.combat

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

/**
 * Bottom-center stamina bar. Renders only when the local player has received
 * a stamina value from the server (i.e. is a registered combatant).
 *
 * Hooked from the client mod entry point via Fabric `HudRenderCallback`.
 */
object StaminaBarHud {
    private const val BAR_WIDTH = 182
    private const val BAR_HEIGHT = 5
    private const val BG_COLOR = 0x80000000.toInt()
    private const val FG_COLOR = 0xFFFFAA00.toInt()
    private const val BORDER_COLOR = 0xFF000000.toInt()

    fun render(ctx: DrawContext) {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return
        val (cur, max) = CombatStateClient.localStamina(player.id) ?: return
        if (max <= 0) return

        val sw = ctx.scaledWindowWidth
        val sh = ctx.scaledWindowHeight
        val x = (sw - BAR_WIDTH) / 2
        val y = sh - 60

        val pct = (cur.toFloat() / max.toFloat()).coerceIn(0f, 1f)
        val fillW = (BAR_WIDTH * pct).toInt()

        // Border + bg + fill (one z layer; the existing HUDs draw flat).
        ctx.fill(x - 1, y - 1, x + BAR_WIDTH + 1, y + BAR_HEIGHT + 1, BORDER_COLOR)
        ctx.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, BG_COLOR)
        if (fillW > 0) ctx.fill(x, y, x + fillW, y + BAR_HEIGHT, FG_COLOR)

        if (client.debugHud.shouldShowDebugHud()) {
            ctx.drawText(client.textRenderer, Text.literal("$cur / $max"), x, y - 10, 0xFFFFFFFF.toInt(), false)
        }
    }
}
