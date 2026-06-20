package com.canefe.storyclient.client.perception

import com.canefe.storyclient.client.StoryClientConfig
import com.mojang.blaze3d.systems.RenderSystem
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.BufferRenderer
import net.minecraft.client.render.GameRenderer
import net.minecraft.client.render.Tessellator
import net.minecraft.client.render.VertexFormat
import net.minecraft.client.render.VertexFormats
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.util.math.Box
import org.joml.Matrix4f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws each tracked NPC's perception FOV as a flat translucent wedge on the
 * horizontal plane at eye height, fanning from the NPC's eyes to sightRange and
 * spanning yaw ± fovHalfDeg. Cone params come from [FovConeStore]; the NPC's live
 * position + yaw are read from the world entity each frame so the wedge follows
 * movement smoothly.
 */
object FovConeRenderer {

    private const val RENDER_DISTANCE = 48.0
    private const val ARC_STEPS = 24

    // Low-alpha cyan fill; apex slightly brighter than the arc edge for a soft fade.
    private const val APEX_ARGB = 0x5020D0FF.toInt()
    private const val ARC_ARGB = 0x1020D0FF.toInt()

    private val entityIdCache = ConcurrentHashMap<UUID, Int>()

    /**
     * (x, z) offsets from the apex for the arc boundary, sampled across the FOV.
     * Minecraft yaw 0 = +Z (south); forward unit vector = (-sin(yaw), 0, cos(yaw)).
     * The wedge sweeps from yawRad - fovHalfDeg to yawRad + fovHalfDeg.
     */
    fun arcPoints(yawRad: Double, fovHalfDeg: Float, sightRange: Float, steps: Int): List<Pair<Float, Float>> {
        val half = Math.toRadians(fovHalfDeg.toDouble())
        val out = ArrayList<Pair<Float, Float>>(steps + 1)
        for (i in 0..steps) {
            val a = yawRad - half + (2.0 * half) * i / steps
            val x = (-sin(a) * sightRange).toFloat()
            val z = (cos(a) * sightRange).toFloat()
            out += x to z
        }
        return out
    }

    fun render(context: WorldRenderContext) {
        if (FovConeStore.isEmpty()) return
        if (!StoryClientConfig.modEnabled) return
        val mc = MinecraftClient.getInstance()
        val world = mc.world ?: return
        val camera = context.camera()
        val camPos = camera.pos
        val cameraEntity = camera.focusedEntity ?: return
        val matrices = context.matrixStack() ?: return
        val tickDelta = context.tickCounter()?.getTickDelta(false) ?: 1f

        val seen = HashSet<UUID>()
        for (c in FovConeStore.params()) {
            seen += c.uuid
            val entity = findEntity(world, c.uuid, c.entityId, cameraEntity) ?: continue
            if (entity.squaredDistanceTo(cameraEntity) > RENDER_DISTANCE * RENDER_DISTANCE) continue

            // Live interpolated pose.
            val ex = entity.prevX + (entity.x - entity.prevX) * tickDelta
            val ez = entity.prevZ + (entity.z - entity.prevZ) * tickDelta
            val eyeY = entity.getEyeY()
            val yawDeg = entity.getYaw(tickDelta)
            val yawRad = Math.toRadians(yawDeg.toDouble())

            matrices.push()
            matrices.translate(ex - camPos.x, eyeY - camPos.y, ez - camPos.z)
            val matrix = matrices.peek().positionMatrix
            drawWedge(matrix, yawRad, c.fovHalfDeg, c.sightRange)
            matrices.pop()
        }
        entityIdCache.keys.retainAll(seen)
    }

    private fun drawWedge(matrix: Matrix4f, yawRad: Double, fovHalfDeg: Float, sightRange: Float) {
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(false)
        RenderSystem.disableCull()
        RenderSystem.setShader { GameRenderer.getPositionColorProgram() }

        val (ar, ag, ab, aa) = argb(APEX_ARGB)
        val (er, eg, eb, ea) = argb(ARC_ARGB)
        val arc = arcPoints(yawRad, fovHalfDeg, sightRange, ARC_STEPS)

        val buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR)
        for (i in 0 until arc.size - 1) {
            val (x1, z1) = arc[i]
            val (x2, z2) = arc[i + 1]
            // apex (bright) → two arc points (faded) — triangle fan
            buf.vertex(matrix, 0f, 0f, 0f).color(ar, ag, ab, aa)
            buf.vertex(matrix, x1, 0f, z1).color(er, eg, eb, ea)
            buf.vertex(matrix, x2, 0f, z2).color(er, eg, eb, ea)
        }
        BufferRenderer.drawWithGlobalProgram(buf.end())

        RenderSystem.enableCull()
        RenderSystem.depthMask(true)
        RenderSystem.disableBlend()
    }

    private fun argb(argb: Int): FloatArray = floatArrayOf(
        ((argb ushr 16) and 0xFF) / 255f, // r
        ((argb ushr 8) and 0xFF) / 255f,  // g
        (argb and 0xFF) / 255f,           // b
        ((argb ushr 24) and 0xFF) / 255f, // a
    )

    private operator fun FloatArray.component1() = this[0]
    private operator fun FloatArray.component2() = this[1]
    private operator fun FloatArray.component3() = this[2]
    private operator fun FloatArray.component4() = this[3]

    private fun findEntity(world: net.minecraft.world.World, uuid: UUID, entityId: Int, cameraEntity: Entity): Entity? {
        if (entityId >= 0) {
            world.getEntityById(entityId)?.let { return it }
        }
        entityIdCache[uuid]?.let { id ->
            world.getEntityById(id)?.let { return it }
        }
        return try {
            val box = Box.of(cameraEntity.pos, RENDER_DISTANCE * 2, RENDER_DISTANCE * 2, RENDER_DISTANCE * 2)
            val found = world.getOtherEntities(null, box) {
                it is LivingEntity && it.uuid == uuid
            }.firstOrNull()
            found?.let { entityIdCache[uuid] = it.id }
            found
        } catch (e: Exception) {
            null
        }
    }
}
