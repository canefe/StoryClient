package com.canefe.storyclient.client.camera

import com.canefe.storyclient.client.cinematic.SpawnCinematicController
import com.canefe.storyclient.client.mixin.GameRendererAccessor
import net.minecraft.client.MinecraftClient
import net.minecraft.util.Identifier

/**
 * Drives the post-process grade while the out-of-character camera is active.
 * Reuses the spawn cinematic's shader chain
 * (`assets/storyclient/shaders/post/spawn_cinematic.json` — tint + vignette +
 * bloom + radial edge blur) at constant full [STRENGTH], so OOC reads with the
 * same "we've stepped outside the ordinary view" grade as the spawn swoop.
 *
 * Parallels [com.canefe.storyclient.client.cinematic.SpawnCinematicPostEffect]:
 * loads the chain onto the GameRenderer when OOC starts, pushes uniforms each
 * frame, and tears it down when OOC ends. The two never run at once — OOC can't
 * activate while the cinematic owns the camera — but we still guard on it so a
 * stray overlap can't leave a double-loaded processor.
 *
 * [tick] must be called every client frame (registered from client init).
 */
object OocPostEffect {

    // Full resource path to the post-effect chain JSON (1.21.1 passes the id
    // straight through to the resource factory).
    private val EFFECT_ID = Identifier.of("storyclient", "shaders/post/spawn_cinematic.json")

    /** Constant filter strength while OOC — no ramp (per design). */
    private const val STRENGTH = 1.0f

    /** Whether we currently have the effect loaded onto the GameRenderer. */
    private var loaded = false

    /** Wall-clock start, only for the shader's animated `Time` uniform. */
    private var startMs: Long = 0L

    fun tick() {
        val mc = MinecraftClient.getInstance()
        val renderer = mc.gameRenderer as GameRendererAccessor
        val active = OocCameraController.isActive &&
            !SpawnCinematicController.isActive &&
            !com.canefe.storyclient.client.confrontation.ConfrontationState.active

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
                // GameRenderer dropped the processor (resource reload, etc.) —
                // forget it so the next active frame reloads.
                loaded = false
                return
            }
        processor.setUniforms("Strength", STRENGTH)
        processor.setUniforms("Time", (System.currentTimeMillis() - startMs) / 1000.0f)
    }
}
