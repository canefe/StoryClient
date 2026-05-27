package com.canefe.storyclient.client.perception

import com.canefe.storyclient.client.recognition.HelixLayout
import com.mojang.blaze3d.systems.RenderSystem
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.render.BufferRenderer
import net.minecraft.client.render.GameRenderer
import net.minecraft.client.render.Tessellator
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.VertexFormat
import net.minecraft.client.render.VertexFormats
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.text.Text
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import org.joml.Quaternionf
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Sticky-action pill + transient perception popups, stacked directly beneath the
 * Helix nametag card. Rendering is gated on the crosshair-targeted entity: only
 * the NPC the player is currently looking at (= the NPC Helix is drawing for
 * this frame) shows its state UI. Layout is read from [HelixLayout] each frame
 * so the column stays pixel-perfectly aligned to the card above.
 */
object PerceptionPopupRenderer {

    private const val RENDER_DISTANCE = 24.0
    private const val LIGHT = 15728880

    private const val RISE_MS  = 350L
    private const val HOLD_MS  = 900L
    private const val EXIT_MS  = 400L
    private const val TOTAL_MS = RISE_MS + HOLD_MS + EXIT_MS

    /** Vertical gap between the Helix card and the first pill, plus between pills. */
    private const val GAP_PX = 3f

    // Pill geometry, in post-scale "pixels".
    private const val PAD_X = 4
    private const val PAD_Y = 2
    private const val BORDER = 1
    private const val Z_BG = 0.05f
    private const val Z_BORDER = 0.03f

    private const val PANEL_BG = 0xCC101418.toInt()
    private const val PANEL_BORDER = 0xFF2A2F36.toInt()

    private data class Popup(
        val label: String,
        val type: PopupType,
        val entityId: Int = -1,
        val startMs: Long = System.currentTimeMillis(),
    )

    private data class PopupStyle(val icon: String, val rgb: Int)

    private val styles = mapOf(
        PopupType.PERCEPTION    to PopupStyle("👁",  0xD0D0D0),
        PopupType.COMBAT_ATTACK to PopupStyle("⚔",  0xFF4444),
        PopupType.COMBAT_ATTACKED to PopupStyle("🛡", 0xFF8800),
        PopupType.MOOD          to PopupStyle("💭",  0xAADDFF),
        PopupType.AGGRESSION    to PopupStyle("😡",  0xFF2200),
        PopupType.ACTION        to PopupStyle("➤",  0xC8E6C9),
    )

    private val popups = ConcurrentHashMap<UUID, ArrayDeque<Popup>>()
    private val actionPopups = ConcurrentHashMap<UUID, Popup>()
    private val entityIdCache = ConcurrentHashMap<UUID, Int>()

    fun onPerception(npcUuid: UUID, perceivedLabel: String, type: PopupType = PopupType.PERCEPTION, entityId: Int = -1) {
        if (type == PopupType.ACTION) {
            if (perceivedLabel.isBlank()) {
                actionPopups.remove(npcUuid)
            } else {
                val existing = actionPopups[npcUuid]
                if (existing == null || existing.label != perceivedLabel) {
                    actionPopups[npcUuid] = Popup(perceivedLabel, PopupType.ACTION, entityId)
                }
            }
            return
        }
        popups.getOrPut(npcUuid) { ArrayDeque() }.addLast(Popup(perceivedLabel, type, entityId))
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

        // Always clear expired transient popups so we don't accumulate them across
        // frames even when nothing is being looked at (otherwise old ones would
        // pop back into view the moment the crosshair touches the entity).
        val expired = mutableListOf<UUID>()
        for ((uuid, queue) in popups.entries.toList()) {
            queue.removeAll { now - it.startMs > TOTAL_MS }
            if (queue.isEmpty()) expired += uuid
        }
        expired.forEach { popups.remove(it) }
        entityIdCache.keys.retainAll { popups.containsKey(it) || actionPopups.containsKey(it) }

        // Gate: only render for the NPC the Helix card was drawn for this frame.
        // No card → no pills. Iterate every NPC with queued state and ask
        // HelixLayout whether that uuid is the current crosshair target.
        val candidates: Set<UUID> = (actionPopups.keys.toSet()) + popups.keys
        for (uuid in candidates) {
            val layout = HelixLayout.get(uuid) ?: continue // not crosshair-targeted → skip
            val entity = findEntity(world, uuid, hintEntityId(uuid), cameraEntity) ?: continue
            if (entity.squaredDistanceTo(cameraEntity) > RENDER_DISTANCE * RENDER_DISTANCE) continue

            val rowH = tr.fontHeight + PAD_Y * 2 + BORDER * 2
            // First row sits one GAP below the card's bottom edge.
            var rowTopY = layout.cardBottomY + GAP_PX

            // Sticky ACTION first (immediately under the card).
            actionPopups[uuid]?.let { popup ->
                val slide = slideIn(now - popup.startMs)
                drawPillAt(entity, popup, layout, slide, 1f, rowTopY, tickDelta, camPos, matrices, consumers, tr)
                rowTopY += rowH + GAP_PX
            }

            // Then transient perception popups, oldest at top.
            popups[uuid]?.forEach { popup ->
                val slide = slideIn(now - popup.startMs)
                val alpha = transientAlpha(now - popup.startMs)
                drawPillAt(entity, popup, layout, slide, alpha, rowTopY, tickDelta, camPos, matrices, consumers, tr)
                rowTopY += rowH + GAP_PX
            }
        }
    }

    private fun hintEntityId(uuid: UUID): Int =
        actionPopups[uuid]?.entityId
            ?: popups[uuid]?.firstOrNull()?.entityId
            ?: -1

    /**
     * Draws a single pill anchored at the same world point Helix uses (top of the
     * bounding box) with the same scale and right-edge alignment. [rowTopY] is the
     * post-scale Y of this pill's top edge (descending Y, since SCALE is negated).
     */
    @Suppress("LongParameterList")
    private fun drawPillAt(
        entity: Entity,
        popup: Popup,
        layout: HelixLayout.State,
        slide: Float,
        alpha: Float,
        rowTopY: Float,
        tickDelta: Float,
        camPos: Vec3d,
        matrices: net.minecraft.client.util.math.MatrixStack,
        consumers: VertexConsumerProvider,
        tr: TextRenderer,
    ) {
        val style = styles[popup.type] ?: styles[PopupType.PERCEPTION]!!

        // Use the entity's interpolated position for X/Z but Helix's anchorMaxY for Y
        // so the pill column stays glued to the card even if the entity bobs.
        val prevX = entity.prevX; val prevZ = entity.prevZ
        val ex = prevX + (entity.x - prevX) * tickDelta
        val ez = prevZ + (entity.z - prevZ) * tickDelta
        val anchor = Vec3d(ex, layout.anchorMaxY, ez)

        val diff = camPos.subtract(anchor)
        val yaw = -(atan2(diff.z, diff.x) + PI / 2.0)
        val horizontalDist = sqrt(diff.x * diff.x + diff.z * diff.z)
        val pitch = atan2(diff.y, horizontalDist)

        matrices.push()
        matrices.translate(anchor.x - camPos.x, anchor.y - camPos.y, anchor.z - camPos.z)
        matrices.multiply(Quaternionf().rotationY(yaw.toFloat()))
        matrices.multiply(Quaternionf().rotationX(pitch.toFloat()))
        // Match Helix's scale exactly so the pill sits in the same coordinate system.
        matrices.scale(-layout.scale, -layout.scale, layout.scale)

        val labelText = Text.literal("${style.icon} ${popup.label}")
        val textW = tr.getWidth(labelText)
        val pillW = textW + PAD_X * 2 + BORDER * 2
        val pillH = tr.fontHeight + PAD_Y * 2 + BORDER * 2

        // Slide-in from the right (further negative X) toward the card's right edge.
        val slideOffset = (1f - slide) * 18f
        val rightEdgeX = layout.cardRightX - slideOffset
        val cx = rightEdgeX - pillW
        val cy = rowTopY

        val alphaInt = (alpha * 255).toInt().coerceIn(0, 255)
        val bgArgb = (PANEL_BG and 0x00FFFFFF) or (((PANEL_BG ushr 24) * alphaInt / 255) shl 24)
        val borderArgb = (PANEL_BORDER and 0x00FFFFFF) or (((PANEL_BORDER ushr 24) * alphaInt / 255) shl 24)
        drawPill(matrices.peek().positionMatrix, cx, cy, pillW.toFloat(), pillH.toFloat(), bgArgb, borderArgb)

        val textColor = (alphaInt shl 24) or style.rgb
        val textX = cx + BORDER + PAD_X
        val textY = cy + BORDER + PAD_Y
        tr.draw(
            labelText, textX, textY, textColor, false,
            matrices.peek().positionMatrix, consumers,
            TextRenderer.TextLayerType.SEE_THROUGH, 0, LIGHT,
        )

        matrices.pop()
    }

    private fun drawPill(matrix: Matrix4f, x: Float, y: Float, w: Float, h: Float, bg: Int, border: Int) {
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(true)
        RenderSystem.setShader { GameRenderer.getPositionColorProgram() }

        val tessellator = Tessellator.getInstance()
        val buf = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR)

        fun fill(x1: Float, y1: Float, x2: Float, y2: Float, argb: Int, z: Float) {
            val a = ((argb ushr 24) and 0xFF) / 255f
            val r = ((argb ushr 16) and 0xFF) / 255f
            val g = ((argb ushr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            buf.vertex(matrix, x1, y2, z).color(r, g, b, a)
            buf.vertex(matrix, x2, y2, z).color(r, g, b, a)
            buf.vertex(matrix, x2, y1, z).color(r, g, b, a)
            buf.vertex(matrix, x1, y1, z).color(r, g, b, a)
        }

        fill(x, y, x + w, y + h, bg, Z_BG)
        fill(x, y, x + w, y + BORDER, border, Z_BORDER)
        fill(x, y + h - BORDER, x + w, y + h, border, Z_BORDER)
        fill(x, y, x + BORDER, y + h, border, Z_BORDER)
        fill(x + w - BORDER, y, x + w, y + h, border, Z_BORDER)

        BufferRenderer.drawWithGlobalProgram(buf.end())
        RenderSystem.disableBlend()
    }

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

    private fun slideIn(elapsed: Long): Float {
        if (elapsed >= RISE_MS) return 1f
        val t = elapsed.toFloat() / RISE_MS
        return 1f - (1f - t) * (1f - t)
    }

    private fun transientAlpha(elapsed: Long): Float = when {
        elapsed < RISE_MS -> (elapsed.toFloat() / RISE_MS).coerceIn(0f, 1f)
        elapsed < RISE_MS + HOLD_MS -> 1f
        else -> (1f - (elapsed - RISE_MS - HOLD_MS).toFloat() / EXIT_MS).coerceIn(0f, 1f)
    }
}
