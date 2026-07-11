package com.canefe.storyclient.client.confrontation

import com.canefe.storyclient.client.mixin.GameRendererAccessor
import net.minecraft.client.MinecraftClient
import net.minecraft.util.Identifier

/**
 * Drives the cinematic post-process filter during a LOCKED confrontation. Reuses
 * the spawn cinematic's shader chain (green/sepia tint + vignette + bloom + edge
 * blur), gated on [ConfrontationState.active]. Mirror of
 * [com.canefe.storyclient.client.cinematic.SpawnCinematicPostEffect].
 *
 * [tick] must be called every client frame.
 */
object ConfrontationPostEffect {

    private val EFFECT_ID = Identifier.of("storyclient", "shaders/post/spawn_cinematic.json")

    private var loaded = false
    private var startMs: Long = 0L

    fun tick() {
        val mc = MinecraftClient.getInstance()
        val renderer = mc.gameRenderer as GameRendererAccessor
        // Yield to the spawn cinematic (it owns the processor during boot). Only
        // one post-effect may own the single GameRenderer processor slot per
        // frame — without this, two effects thrash load/disable → flicker.
        val active = ConfrontationState.active &&
            !com.canefe.storyclient.client.cinematic.SpawnCinematicController.isActive

        if (active && !loaded) {
            renderer.`storyclient$loadPostProcessor`(EFFECT_ID)
            startMs = System.currentTimeMillis()
            loaded = true
        } else if (!active && loaded) {
            renderer.`storyclient$disablePostProcessor`()
            loaded = false
            return
        }

        if (!loaded) return

        val processor = renderer.`storyclient$getPostProcessor`()
            ?: run {
                loaded = false
                return
            }
        processor.setUniforms("Strength", 1.0f)
        processor.setUniforms("Time", (System.currentTimeMillis() - startMs) / 1000.0f)
    }
}
