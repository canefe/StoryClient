package com.canefe.storyclient.client.sim

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.model.json.ModelTransformationMode
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.math.Vec3d
import org.joml.Quaternionf
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Client-side floating item that arcs from a giver entity to a receiver entity
 * over [DURATION_MS], spinning, scaling in then out, fading at the end. Fed by
 * the story:item_transfer packet. Renders nothing server-side.
 */
object ItemHologramRenderer {
    private const val DURATION_MS = 800L
    private const val MAX_HOLOS = 32
    private const val APEX_LIFT = 1.2
    private const val LIGHT = 15728880
    private const val BADGE_SCALE = 0.025f

    private data class Holo(
        val fromId: Int,
        val toId: Int,
        val stack: ItemStack,
        val qty: Int,
        val startMs: Long = System.currentTimeMillis(),
    )

    private val holos = ConcurrentLinkedQueue<Holo>()

    fun spawn(payload: ItemTransferPayload) {
        val id = Identifier.tryParse(payload.materialId) ?: return
        val item = Registries.ITEM.get(id)
        val stack = ItemStack(item)
        while (holos.size >= MAX_HOLOS) holos.poll()
        holos.add(Holo(payload.fromEntityId, payload.toEntityId, stack, payload.qty))
    }

    /** Arc-interpolated position with a parabolic apex lift. Pure; testable. */
    fun arc(from: Vec3d, to: Vec3d, t: Double, lift: Double = APEX_LIFT): Vec3d {
        val base = from.lerp(to, t)
        val arcY = 4.0 * lift * t * (1.0 - t) // parabola peaking at t=0.5
        return Vec3d(base.x, base.y + arcY, base.z)
    }

    /** Scale curve: ease-in over first 15%, hold, ease-out over last 25%. */
    fun scaleFor(t: Double): Float = when {
        t < 0.15 -> (t / 0.15 * 0.5).toFloat()
        t > 0.75 -> ((1.0 - t) / 0.25 * 0.5).toFloat()
        else -> 0.5f
    }

    /** Alpha: opaque until 75%, then linear fade to 0. */
    fun alphaFor(t: Double): Float = if (t > 0.75) ((1.0 - t) / 0.25).toFloat() else 1f

    fun render(context: WorldRenderContext) {
        if (holos.isEmpty()) return
        val mc = MinecraftClient.getInstance()
        val world = mc.world ?: return
        val matrices = context.matrixStack() ?: return
        val consumers = context.consumers() ?: return
        val camPos = context.camera().pos
        val tickDelta = context.tickCounter()?.getTickDelta(false) ?: 1f
        val now = System.currentTimeMillis()
        val tr = mc.textRenderer

        val it = holos.iterator()
        while (it.hasNext()) {
            val h = it.next()
            val elapsed = now - h.startMs
            if (elapsed >= DURATION_MS) { it.remove(); continue }
            val from = world.getEntityById(h.fromId)
            val to = world.getEntityById(h.toId)
            if (from == null || to == null) { it.remove(); continue }

            val fromPos = Vec3d(
                from.prevX + (from.x - from.prevX) * tickDelta,
                from.prevY + (from.y - from.prevY) * tickDelta + from.height * 0.7,
                from.prevZ + (from.z - from.prevZ) * tickDelta,
            )
            val toPos = Vec3d(
                to.prevX + (to.x - to.prevX) * tickDelta,
                to.prevY + (to.y - to.prevY) * tickDelta + to.height * 0.7,
                to.prevZ + (to.z - to.prevZ) * tickDelta,
            )
            val t = elapsed.toDouble() / DURATION_MS
            val pos = arc(fromPos, toPos, t)
            val scale = scaleFor(t)
            val spin = (elapsed % 1000L) / 1000f * 360f

            matrices.push()
            matrices.translate(pos.x - camPos.x, pos.y - camPos.y, pos.z - camPos.z)

            // Spinning item, scaled in/out.
            matrices.push()
            matrices.multiply(Quaternionf().rotationY(Math.toRadians(spin.toDouble()).toFloat()))
            matrices.scale(scale, scale, scale)
            mc.itemRenderer.renderItem(
                h.stack,
                ModelTransformationMode.GROUND,
                LIGHT,
                OverlayTexture.DEFAULT_UV,
                matrices,
                consumers,
                world,
                0,
            )
            matrices.pop()

            // Quantity badge above the item, billboarded toward the camera.
            if (h.qty > 1) {
                matrices.push()
                val diff = camPos.subtract(pos)
                val yaw = -(atan2(diff.z, diff.x) + PI / 2.0)
                val horizontalDist = sqrt(diff.x * diff.x + diff.z * diff.z)
                val pitch = atan2(diff.y, horizontalDist)
                matrices.multiply(Quaternionf().rotationY(yaw.toFloat()))
                matrices.multiply(Quaternionf().rotationX(pitch.toFloat()))
                matrices.scale(-BADGE_SCALE, -BADGE_SCALE, BADGE_SCALE)
                val text = Text.literal("x${h.qty}")
                val halfW = tr.getWidth(text) / 2f
                val alphaInt = (alphaFor(t) * 255).toInt().coerceIn(0, 255)
                val color = (alphaInt shl 24) or 0xFFFFFF
                tr.draw(
                    text, -halfW, -14f, color, false,
                    matrices.peek().positionMatrix, consumers,
                    TextRenderer.TextLayerType.SEE_THROUGH, 0, LIGHT,
                )
                matrices.pop()
            }

            matrices.pop()
        }
    }
}
