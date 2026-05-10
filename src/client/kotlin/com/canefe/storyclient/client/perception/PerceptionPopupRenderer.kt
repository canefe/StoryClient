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
        val type: PopupType,
        val startMs: Long = System.currentTimeMillis(),
    )

    private data class PopupStyle(val icon: String, val rgb: Int)

    private val styles = mapOf(
        PopupType.PERCEPTION    to PopupStyle("👁",  0xD0D0D0),
        PopupType.COMBAT_ATTACK to PopupStyle("⚔",  0xFF4444),
        PopupType.COMBAT_ATTACKED to PopupStyle("🛡", 0xFF8800),
        PopupType.MOOD          to PopupStyle("💭",  0xAADDFF),
        PopupType.AGGRESSION    to PopupStyle("😡",  0xFF2200),
    )

    private val popups = ConcurrentHashMap<UUID, ArrayDeque<Popup>>()
    private val entityIdCache = ConcurrentHashMap<UUID, Int>()

    fun onPerception(npcUuid: UUID, perceivedLabel: String, type: PopupType = PopupType.PERCEPTION) {
        popups.getOrPut(npcUuid) { ArrayDeque() }.addLast(Popup(perceivedLabel, type))
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

        for ((uuid, queue) in popups.entries.toList()) {
            // Prune finished entries
            while (queue.isNotEmpty() && now - queue.first().startMs > TOTAL_MS) {
                queue.removeFirst()
            }
            if (queue.isEmpty()) {
                expired += uuid
                entityIdCache.remove(uuid)
                continue
            }

            val entity = findEntity(world, uuid, cameraEntity) ?: continue
            if (entity.squaredDistanceTo(cameraEntity) > RENDER_DISTANCE * RENDER_DISTANCE) continue

            // Render each queued popup stacked vertically (most recent on top)
            queue.forEachIndexed { stackIndex, popup ->
                val elapsed = now - popup.startMs
                val (yDelta, alpha) = animate(elapsed)
                val style = styles[popup.type] ?: styles[PopupType.PERCEPTION]!!

                val prevX = entity.prevX; val prevY = entity.prevY; val prevZ = entity.prevZ
                val ex = prevX + (entity.x - prevX) * tickDelta
                val ey = prevY + (entity.y - prevY) * tickDelta
                val ez = prevZ + (entity.z - prevZ) * tickDelta
                val stackOffset = stackIndex * 0.30
                val yPos = ey + entity.height + Y_OFFSET_CENTER + yDelta + stackOffset

                val pos = Vec3d(ex, yPos, ez)

                matrices.push()
                matrices.translate(pos.x - camPos.x, pos.y - camPos.y, pos.z - camPos.z)

                val diff = camPos.subtract(pos)
                val yaw = -(atan2(diff.z, diff.x) + PI / 2.0)
                val horizontalDist = sqrt(diff.x * diff.x + diff.z * diff.z)
                val pitch = atan2(diff.y, horizontalDist)
                matrices.multiply(Quaternionf().rotationY(yaw.toFloat()))
                matrices.multiply(Quaternionf().rotationX(pitch.toFloat()))

                matrices.scale(-SCALE, -SCALE, SCALE)

                val alphaInt = (alpha * 255).toInt().coerceIn(0, 255)
                val rgb = style.rgb
                val color = (alphaInt shl 24) or rgb
                val text = Text.literal("${style.icon} ${popup.label}")
                val halfW = tr.getWidth(text) / 2f

                val shadowRgb = run {
                    val r = (rgb shr 16 and 0xFF) * 6 / 10
                    val g = (rgb shr 8 and 0xFF) * 6 / 10
                    val b = (rgb and 0xFF) * 6 / 10
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
