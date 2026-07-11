package com.canefe.storyclient.client.screen

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.util.math.MathHelper

/**
 * Full-screen black fade-OUT overlay drawn over the world once a join/world-change
 * transition completes.
 *
 * The connect + terrain screens render solid black (see `ConnectScreenMixin` and
 * [BlackFadeTerrainScreen]). When the terrain screen closes — the world is now
 * loaded and the HUD renders — it calls [beginFadeOut]. From then on this overlay
 * paints the Skyrim loading screen (or a black rect, if no art was active) over the
 * world whose alpha ramps 255 → 0, revealing the world smoothly instead of a hard
 * cut. Once the ramp finishes it calls [LoadingScreenRenderer.reset] so the next
 * join reshuffles the art + tip.
 *
 * Hooked from the client entry point via `HudRenderCallback`, which only fires
 * when a world is present and no screen is open — exactly the post-load window we
 * want to fade through.
 */
object ScreenFadeOverlay {

    /** Milliseconds to ramp from fully black to fully transparent. */
    private const val FADE_OUT_MS = 600.0f

    /** System-time (ms) at which the fade-out started, or null when idle. */
    @Volatile
    private var fadeStartMs: Long? = null

    /** Called from [BlackFadeTerrainScreen.close]; starts the reveal. */
    fun beginFadeOut() {
        fadeStartMs = System.currentTimeMillis()
    }

    fun render(ctx: DrawContext) {
        val start = fadeStartMs ?: return

        // Don't paint over a screen (e.g. the player opened a menu mid-fade) or
        // when there's no world yet — only fade the live world in.
        val client = MinecraftClient.getInstance()
        if (client.world == null) {
            fadeStartMs = null
            LoadingScreenRenderer.reset()
            return
        }

        val elapsed = (System.currentTimeMillis() - start).toFloat()
        val t = MathHelper.clamp(elapsed / FADE_OUT_MS, 0.0f, 1.0f)
        if (t >= 1.0f) {
            fadeStartMs = null
            LoadingScreenRenderer.reset()
            return
        }

        val w = client.window.scaledWidth
        val h = client.window.scaledHeight
        val alpha = 1.0f - t

        // Fade the loading art (image + tip) away over the live world if it was
        // active; otherwise fall back to a plain black rect ramping to transparent.
        if (LoadingScreenRenderer.isActive()) {
            LoadingScreenRenderer.render(ctx, w, h, alpha)
        } else {
            ctx.fill(0, 0, w, h, ((alpha * 255f).toInt() and 0xFF) shl 24)
        }
    }
}
