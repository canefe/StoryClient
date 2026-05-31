package com.canefe.storyclient.client.emote

import com.mojang.blaze3d.systems.RenderSystem
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.BufferRenderer
import net.minecraft.client.render.GameRenderer
import net.minecraft.client.render.Tessellator
import net.minecraft.client.render.VertexFormat
import net.minecraft.client.render.VertexFormats
import net.minecraft.entity.Entity
import net.minecraft.util.Identifier
import org.joml.Matrix4f
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Renders floating emote icons (laugh, cry, anger, pain, shock) above NPCs.
 *
 * Lifecycle per emote: RISE (350ms) → HOLD (900ms) → EXIT (400ms) — matches
 * the perception popup curve so the visual rhythm feels consistent. One emote
 * at a time per entity; a new emote replaces the previous one. Unknown emote
 * ids are silently dropped (forward-compat: sim can ship new ids before the
 * client knows them).
 *
 * Unlike [com.canefe.storyclient.client.perception.PerceptionPopupRenderer],
 * emotes are always visible — they do not gate on crosshair target. The point
 * of an emote is that players notice it from across the room.
 *
 * This file contains scheduling/data only. The GL draw call lives in a sibling
 * `render(WorldRenderContext)` method added in a follow-up commit.
 */
object EmoteRenderer {

    const val RISE_MS = 350L
    const val HOLD_MS = 2500L
    const val EXIT_MS = 400L
    const val TOTAL_MS = RISE_MS + HOLD_MS + EXIT_MS

    data class Active(
        val emoteId: String,
        val texture: Identifier,
        val startMs: Long,
    )

    private val active = ConcurrentHashMap<Int, Active>()

    private val allowlist: Map<String, Identifier> = mapOf(
        "cry"   to Identifier.of("storyclient", "textures/emote/cry.png"),
        "anger" to Identifier.of("storyclient", "textures/emote/anger.png"),
        "pain"  to Identifier.of("storyclient", "textures/emote/pain.png"),
        "laugh" to Identifier.of("storyclient", "textures/emote/laugh.png"),
        "shock" to Identifier.of("storyclient", "textures/emote/shock.png"),
    )

    /** Server→client entry point. Called from the payload handler. */
    fun onEmote(entityId: Int, emoteId: String, nowMs: Long = System.currentTimeMillis()) {
        val tex = allowlist[emoteId] ?: return // unknown id — drop silently
        active[entityId] = Active(emoteId, tex, nowMs)
    }

    /** Drops emotes whose lifetime is exhausted. Call once per frame. */
    fun sweep(nowMs: Long = System.currentTimeMillis()) {
        val cutoff = nowMs - TOTAL_MS
        active.entries.removeIf { it.value.startMs < cutoff }
    }

    /**
     * Per-frame draw. For each active emote whose entity is still in the world,
     * draws a billboarded textured quad anchored at the entity's head + 0.5
     * blocks, rising another 0.5 blocks over the emote's lifetime, with alpha
     * easing in (RISE) and out (EXIT).
     */
    fun render(context: WorldRenderContext) {
        if (active.isEmpty()) return
        sweep()
        val mc = MinecraftClient.getInstance()
        val world = mc.world ?: return
        val camera = context.camera()
        val camPos = camera.pos
        val matrices = context.matrixStack() ?: return
        val now = System.currentTimeMillis()

        for ((entityId, emote) in active) {
            val entity: Entity = world.getEntityById(entityId) ?: continue
            val age = now - emote.startMs
            if (age < 0 || age > TOTAL_MS) continue

            val alpha = alphaFor(age)
            val rise = riseFor(age)
            val ex = entity.x
            val ey = entity.y + entity.height + 0.5 + rise
            val ez = entity.z

            val half = 0.125f

            matrices.push()
            matrices.translate(ex - camPos.x, ey - camPos.y, ez - camPos.z)
            matrices.multiply(camera.rotation)
            val m: Matrix4f = matrices.peek().positionMatrix

            RenderSystem.enableBlend()
            RenderSystem.defaultBlendFunc()
            RenderSystem.setShader { GameRenderer.getPositionTexProgram() }
            RenderSystem.setShaderTexture(0, emote.texture)
            RenderSystem.setShaderColor(1f, 1f, 1f, alpha)

            val buf = Tessellator.getInstance().begin(
                VertexFormat.DrawMode.QUADS,
                VertexFormats.POSITION_TEXTURE,
            )
            buf.vertex(m, -half, -half, 0f).texture(0f, 1f)
            buf.vertex(m,  half, -half, 0f).texture(1f, 1f)
            buf.vertex(m,  half,  half, 0f).texture(1f, 0f)
            buf.vertex(m, -half,  half, 0f).texture(0f, 0f)
            BufferRenderer.drawWithGlobalProgram(buf.end())

            RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
            RenderSystem.disableBlend()
            matrices.pop()
        }
    }

    private fun alphaFor(ageMs: Long): Float = when {
        ageMs < RISE_MS -> ageMs.toFloat() / RISE_MS.toFloat()
        ageMs < RISE_MS + HOLD_MS -> 1f
        else -> {
            val t = (ageMs - RISE_MS - HOLD_MS).toFloat() / EXIT_MS.toFloat()
            (1f - t).coerceAtLeast(0f)
        }
    }

    /** Vertical lift in world units; total 0.5 blocks over [TOTAL_MS]. */
    private fun riseFor(ageMs: Long): Double {
        val t = min(1.0, ageMs.toDouble() / TOTAL_MS.toDouble())
        return 0.5 * t
    }

    /** Test-only accessor. */
    internal fun activeMapForTest(): Map<Int, Active> = active
}
