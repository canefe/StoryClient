package com.canefe.storyclient.client.combat

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext

/**
 * Subtle screen-edge flash while the local player is in a parry window
 * (Blocking with parryWindowTicksLeft > 0). Helps players time taps.
 */
object ParryFlashHud {
    private const val FLASH_COLOR = 0x4055AAFF.toInt() // soft blue, ~25% alpha

    fun render(ctx: DrawContext) {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return
        val payload = CombatStateClient.get(player.id) ?: return
        if (payload.stateOrdinal != 4) return // 4 = Blocking
        if (payload.ticksLeft <= 0) return // window closed

        val sw = ctx.scaledWindowWidth
        val sh = ctx.scaledWindowHeight
        val border = 4
        // Top + bottom + left + right thin frames.
        ctx.fill(0, 0, sw, border, FLASH_COLOR)
        ctx.fill(0, sh - border, sw, sh, FLASH_COLOR)
        ctx.fill(0, 0, border, sh, FLASH_COLOR)
        ctx.fill(sw - border, 0, sw, sh, FLASH_COLOR)
    }
}
