package com.canefe.storyclient.client.combat

import net.minecraft.client.MinecraftClient
import kotlin.math.sin
import kotlin.random.Random

/**
 * Per-tick camera effects driven by [HitOutcomePayload] and player state
 * pushes. v1 implements:
 *
 *   - Screen shake on local stagger (configurable intensity).
 *   - Brief slow-mo (0.5×) on parry-receive — implemented as a render tickrate
 *     hint via [MinecraftClient.targetedEntity]. Real slow-mo requires deeper
 *     engine hooks; v1 ships only the shake and reserves slow-mo as a TODO
 *     until we have a safe time-multiplier injection point.
 *
 * Activation: registered on `ClientTickEvents.END_CLIENT_TICK` from
 * `NPCMessageParserClient`.
 */
object CombatCameraEffects {
    private var shakeTicksLeft = 0
    private var shakeIntensity = 0f

    fun onOutcome(payload: HitOutcomePayload) {
        val client = MinecraftClient.getInstance()
        val localId = client.player?.id ?: return
        // Bad block + parry-receive both warrant a shake.
        if (payload.defenderEntityId != localId) return
        if (payload.outcomeOrdinal == 4 || payload.outcomeOrdinal == 0) {
            startShake(8, 1.5f)
        }
    }

    fun onLocalStateChange(payload: CombatStatePushPayload) {
        // 5 = Staggered.
        if (payload.stateOrdinal == 5) startShake(14, 2.5f)
    }

    fun tick() {
        if (shakeTicksLeft > 0) shakeTicksLeft--
    }

    /**
     * Camera-shake yaw offset for the current frame. Sampled from the
     * camera mixin (call site is the renderer); falls back to 0 if no shake.
     */
    fun currentShakeYawOffset(): Float {
        if (shakeTicksLeft <= 0) return 0f
        val intensity = shakeIntensity * com.canefe.storyclient.client.StoryClientConfig.combatScreenShakeIntensity
        return (sin(System.nanoTime() / 1.0e7) * intensity).toFloat()
    }

    fun currentShakePitchOffset(): Float {
        if (shakeTicksLeft <= 0) return 0f
        val intensity = shakeIntensity * com.canefe.storyclient.client.StoryClientConfig.combatScreenShakeIntensity
        return (sin(System.nanoTime() / 7.0e6) * intensity * 0.5).toFloat()
    }

    private fun startShake(ticks: Int, intensity: Float) {
        shakeTicksLeft = maxOf(shakeTicksLeft, ticks)
        shakeIntensity = maxOf(shakeIntensity, intensity)
    }
}
