package com.canefe.storyclient.client.cinematic

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext

/**
 * Faint full-screen white flash painted at each birds-eye snap of the spawn
 * cinematic, so the stepped descent reads as a deliberate cut rather than a
 * jarring jump. Opacity comes from [SpawnCinematicController.glowAlpha], which
 * peaks at the start of every step and fades fast.
 *
 * Hooked from the client entry point via `HudRenderCallback`, drawn after the
 * other HUD elements so the flash sits on top of the scene.
 */
object SpawnCinematicGlowOverlay {

    fun render(ctx: DrawContext) {
        // Fire the per-step ender-dragon-flap stinger (edge-detected inside).
        SpawnCinematicController.tickStepSounds()

        val a = SpawnCinematicController.glowAlpha()
        if (a <= 0f) return

        val client = MinecraftClient.getInstance()
        if (client.world == null) return

        val alpha = (a.coerceIn(0f, 1f) * 255f).toInt() and 0xFF
        val color = (alpha shl 24) or 0x00FFFFFF // white with computed alpha
        ctx.fill(0, 0, client.window.scaledWidth, client.window.scaledHeight, color)
    }
}
