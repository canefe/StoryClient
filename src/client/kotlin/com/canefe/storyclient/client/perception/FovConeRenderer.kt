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
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws each tracked NPC's perception FOV overlay. Two modes, selected by
 * [StoryClientConfig.fovCone3D]:
 *  - 2D (default): a flat translucent wedge on the horizontal plane at eye
 *    height, yaw-only, spanning yaw ± fovHalfDeg.
 *  - 3D: a solid translucent circular cone whose axis follows the NPC's yaw AND
 *    pitch, with half-angle fovHalfDeg in every direction — matching the
 *    server's `inFov` (acos(facing·dir) <= fovHalfDeg).
 * Cone params come from [FovConeStore]; the NPC's live position, yaw (and pitch
 * in 3D) are read from the world entity each frame so the overlay follows
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
            if (StoryClientConfig.fovCone3D) {
                // 3D: axis follows yaw AND pitch — a solid circular cone matching
                // the server's inFov (acos(facing·dir) <= fovHalfDeg in all directions).
                val pitchDeg = entity.getPitch(tickDelta)
                val axis = facingVector(yawRad, Math.toRadians(pitchDeg.toDouble()))
                drawCone3D(matrix, axis, c.fovHalfDeg, c.sightRange)
            } else {
                drawWedge(matrix, yawRad, c.fovHalfDeg, c.sightRange)
            }
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

    /**
     * Unit forward vector for Minecraft yaw+pitch (radians). Matches the eye
     * `facing` direction the server uses: yaw 0 = +Z, positive pitch looks down.
     */
    fun facingVector(yawRad: Double, pitchRad: Double): Vector3f {
        val cp = cos(pitchRad)
        return Vector3f(
            (-sin(yawRad) * cp).toFloat(),
            (-sin(pitchRad)).toFloat(),
            (cos(yawRad) * cp).toFloat(),
        )
    }

    /**
     * Ring of points on the cone's cap circle at distance [sightRange], swept
     * around [axis] at polar half-angle [fovHalfDeg]. Returns [steps]+1 points
     * (first == last for a closed ring). The full set lies on the sphere of
     * radius sightRange, each at exactly fovHalfDeg from the axis — i.e. the
     * boundary of the server's perception cone.
     */
    fun conePoints(axis: Vector3f, fovHalfDeg: Float, sightRange: Float, steps: Int): List<Vector3f> {
        val ax = Vector3f(axis).normalize()
        // Orthonormal basis (right, up) perpendicular to the axis. Pick a seed
        // that isn't parallel to the axis to avoid a degenerate cross product.
        val seed = if (abs(ax.y) < 0.99f) Vector3f(0f, 1f, 0f) else Vector3f(1f, 0f, 0f)
        val right = Vector3f(seed).cross(ax).normalize()
        val up = Vector3f(ax).cross(right).normalize()

        val half = Math.toRadians(fovHalfDeg.toDouble())
        val sinH = sin(half).toFloat()
        val cosH = cos(half).toFloat()
        val out = ArrayList<Vector3f>(steps + 1)
        for (i in 0..steps) {
            val az = (2.0 * Math.PI * i / steps)
            val ca = cos(az).toFloat()
            val sa = sin(az).toFloat()
            // dir = axis*cosH + (right*ca + up*sa)*sinH, then scaled to sightRange.
            val dir = Vector3f(ax).mul(cosH)
                .add(Vector3f(right).mul(sinH * ca))
                .add(Vector3f(up).mul(sinH * sa))
                .normalize()
                .mul(sightRange)
            out += dir
        }
        return out
    }

    private fun drawCone3D(matrix: Matrix4f, axis: Vector3f, fovHalfDeg: Float, sightRange: Float) {
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(false)
        RenderSystem.disableCull()
        RenderSystem.setShader { GameRenderer.getPositionColorProgram() }

        val (ar, ag, ab, aa) = argb(APEX_ARGB)
        val (er, eg, eb, ea) = argb(ARC_ARGB)
        val ring = conePoints(axis, fovHalfDeg, sightRange, ARC_STEPS)
        // Cap-disc centre: along the axis at the cap's depth (cosH * sightRange).
        val capCenter = Vector3f(axis).normalize()
            .mul((cos(Math.toRadians(fovHalfDeg.toDouble())) * sightRange).toFloat())

        val buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR)
        for (i in 0 until ring.size - 1) {
            val p1 = ring[i]
            val p2 = ring[i + 1]
            // Lateral surface: apex (bright) → two rim points (faded).
            buf.vertex(matrix, 0f, 0f, 0f).color(ar, ag, ab, aa)
            buf.vertex(matrix, p1.x, p1.y, p1.z).color(er, eg, eb, ea)
            buf.vertex(matrix, p2.x, p2.y, p2.z).color(er, eg, eb, ea)
            // Cap disc: centre (bright) → two rim points (faded) so the far end is closed.
            buf.vertex(matrix, capCenter.x, capCenter.y, capCenter.z).color(ar, ag, ab, aa)
            buf.vertex(matrix, p1.x, p1.y, p1.z).color(er, eg, eb, ea)
            buf.vertex(matrix, p2.x, p2.y, p2.z).color(er, eg, eb, ea)
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
