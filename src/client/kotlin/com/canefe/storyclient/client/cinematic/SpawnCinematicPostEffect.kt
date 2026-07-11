package com.canefe.storyclient.client.cinematic

import com.canefe.storyclient.client.mixin.GameRendererAccessor
import net.minecraft.client.MinecraftClient
import net.minecraft.util.Identifier

/**
 * Drives the GTA-style post-process filter that runs only during the spawn
 * cinematic. The effect (green/sepia tint + vignette + soft bloom + radial edge
 * blur) lives in `assets/storyclient/shaders/post/spawn_cinematic.json`; this
 * object loads it onto [net.minecraft.client.render.GameRenderer] when the
 * cinematic starts, pushes the `Strength`/`Time` uniforms each frame, and tears
 * it down when the cinematic ends.
 *
 * `Strength` is [SpawnCinematicController.filterStrength] — full through the snaps
 * and final hold, then ramping to zero across the resolve so the grade dissolves
 * as we land in first-person.
 *
 * [tick] must be called every client frame (registered from the client init).
 */
object SpawnCinematicPostEffect {

    // 1.21.1's GameRenderer.loadPostProcessor passes this id straight to the
    // resource factory without adding any prefix/suffix, so it must be the FULL
    // resource path to the post-effect chain JSON.
    private val EFFECT_ID = Identifier.of("storyclient", "shaders/post/spawn_cinematic.json")

    /** Whether we currently have the effect loaded onto the GameRenderer. */
    private var loaded = false

    /** Wall-clock start used only to feed the shader's animated `Time` uniform. */
    private var startMs: Long = 0L

    fun tick() {
        val mc = MinecraftClient.getInstance()
        val renderer = mc.gameRenderer as GameRendererAccessor
        val active = SpawnCinematicController.isActive

        if (active && !loaded) {
            // loadPostProcessor swaps in our chain; it stays until disabled or the
            // GameRenderer reloads (e.g. resource reload / dimension change).
            renderer.`storyclient$loadPostProcessor`(EFFECT_ID)
            startMs = System.currentTimeMillis()
            loaded = true
        } else if (!active && loaded) {
            renderer.`storyclient$disablePostProcessor`()
            loaded = false
            return
        }

        if (!loaded) return

        // Push per-frame uniforms onto every pass that declares them.
        val processor = renderer.`storyclient$getPostProcessor`()
            ?: run {
                // GameRenderer dropped the processor out from under us (resource
                // reload, etc.) — forget it so the next active frame reloads.
                loaded = false
                return
            }
        processor.setUniforms("Strength", SpawnCinematicController.filterStrength())
        processor.setUniforms("Time", (System.currentTimeMillis() - startMs) / 1000.0f)
    }
}
