package com.canefe.storyclient.client.perception

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.text.Text
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import org.joml.Quaternionf
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

object PerceptionPopupRenderer {

    private const val RENDER_DISTANCE = 24.0
    private const val SCALE = 0.015f
    private const val LIGHT = 15728880

    private const val RISE_MS  = 500L
    private const val HOLD_MS  = 900L
    private const val EXIT_MS  = 400L
    private const val TOTAL_MS = RISE_MS + HOLD_MS + EXIT_MS

    private const val Y_OFFSET_CENTER = 0.55
    private const val Y_ENTRY_DELTA   = -0.30
    private const val Y_EXIT_DELTA    =  0.40

    private data class Popup(
        val label: String,
        val startMs: Long = System.currentTimeMillis(),
    )

    private val popups = ConcurrentHashMap<UUID, Popup>()
    // cache entity id to avoid scanning every frame
    private val entityIdCache = ConcurrentHashMap<UUID, Int>()

    fun onPerception(npcUuid: UUID, perceivedLabel: String) {
        popups[npcUuid] = Popup(perceivedLabel)
    }

    fun render(context: WorldRenderContext) {
        val mc = MinecraftClient.getInstance()
        val world = mc.world ?: return
        val matrices = context.matrixStack() ?: return
        val consumers = context.consumers() ?: return
        val camera = context.camera()
        val camPos = camera.pos
        val cameraEntity = camera.focusedEntity ?: return
        val tickDelta = context.tickCounter()?.getTickDelta(false) ?: 1f
        val now = System.currentTimeMillis()
        val tr = mc.textRenderer

        val expired = mutableListOf<UUID>()

        if (popups.isNotEmpty()) println("[PerceptionPopup] render tick: ${popups.size} active popups")

        for ((uuid, popup) in popups.entries.toList()) {
            val elapsed = now - popup.startMs
            if (elapsed > TOTAL_MS) {
                expired += uuid
                entityIdCache.remove(uuid)
                continue
            }

            val entity = findEntity(world, uuid, cameraEntity) ?: continue
            if (entity.squaredDistanceTo(cameraEntity) > RENDER_DISTANCE * RENDER_DISTANCE) continue

            val (yDelta, alpha) = animate(elapsed)

            val prevX = entity.prevX; val prevY = entity.prevY; val prevZ = entity.prevZ
            val ex = prevX + (entity.x - prevX) * tickDelta
            val ey = prevY + (entity.y - prevY) * tickDelta
            val ez = prevZ + (entity.z - prevZ) * tickDelta
            val yPos = ey + entity.height + Y_OFFSET_CENTER + yDelta

            val pos = Vec3d(ex, yPos, ez)

            matrices.push()
            matrices.translate(pos.x - camPos.x, pos.y - camPos.y, pos.z - camPos.z)

            // Billboard: face camera using yaw+pitch like BubbleRenderer
            val diff = camPos.subtract(pos)
            val yaw = -(atan2(diff.z, diff.x) + PI / 2.0)
            val horizontalDist = sqrt(diff.x * diff.x + diff.z * diff.z)
            val pitch = atan2(diff.y, horizontalDist)
            matrices.multiply(Quaternionf().rotationY(yaw.toFloat()))
            matrices.multiply(Quaternionf().rotationX(pitch.toFloat()))

            matrices.scale(-SCALE, -SCALE, SCALE)

            val alphaInt = (alpha * 255).toInt().coerceIn(0, 255)
            val color = (alphaInt shl 24) or 0xD0D0D0
            val text = Text.literal("👁 ${popup.label}")
            val halfW = tr.getWidth(text) / 2f

            val shadowRgb = run {
                val r = 0xD0 * 6 / 10; val g = 0xD0 * 6 / 10; val b = 0xD0 * 6 / 10
                (r shl 16) or (g shl 8) or b
            }
            val shadowColor = (alphaInt shl 24) or shadowRgb
            val matrix = matrices.peek().positionMatrix

            tr.draw(
                Text.literal(text.string).styled { it.withColor(shadowRgb) },
                -halfW + 1f, 1f, shadowColor, false,
                matrix, consumers, TextRenderer.TextLayerType.SEE_THROUGH, 0, LIGHT,
            )
            tr.draw(
                text, -halfW, 0f, color, false,
                matrix, consumers, TextRenderer.TextLayerType.SEE_THROUGH, 0, LIGHT,
            )

            matrices.pop()
        }

        expired.forEach { popups.remove(it) }
    }

    private fun findEntity(world: net.minecraft.world.World, uuid: UUID, cameraEntity: Entity): Entity? {
        entityIdCache[uuid]?.let { id ->
            world.getEntityById(id)?.let { return it }
        }
        return try {
            val box = Box.of(cameraEntity.pos, RENDER_DISTANCE * 2, RENDER_DISTANCE * 2, RENDER_DISTANCE * 2)
            val found = world.getOtherEntities(null, box) {
                it is LivingEntity && it.uuid == uuid
            }.firstOrNull()
            if (found == null) {
                println("[PerceptionPopup] entity not found for uuid=$uuid in box around ${cameraEntity.pos}")
            }
            found?.let { entityIdCache[uuid] = it.id }
            found
        } catch (e: Exception) {
            println("[PerceptionPopup] findEntity exception: $e")
            null
        }
    }

    private fun animate(elapsed: Long): Pair<Double, Float> = when {
        elapsed < RISE_MS -> {
            val t = elapsed.toFloat() / RISE_MS
            val ease = 1f - (1f - t) * (1f - t)
            Pair(Y_ENTRY_DELTA * (1.0 - ease), ease)
        }
        elapsed < RISE_MS + HOLD_MS -> Pair(0.0, 1f)
        else -> {
            val t = (elapsed - RISE_MS - HOLD_MS).toFloat() / EXIT_MS
            Pair(Y_EXIT_DELTA * (t * t).toDouble(), 1f - t)
        }
    }
}
