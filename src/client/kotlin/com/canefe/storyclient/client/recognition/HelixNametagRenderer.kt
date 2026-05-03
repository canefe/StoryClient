package com.canefe.storyclient.client.recognition

import com.canefe.storyclient.client.wheel.NearbyNPCCache
import com.mojang.blaze3d.systems.RenderSystem
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.render.*
import net.minecraft.entity.LivingEntity
import net.minecraft.text.OrderedText
import net.minecraft.text.Text
import net.minecraft.util.hit.EntityHitResult
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import org.joml.Quaternionf
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Helix-gamemode-style nametag — world-anchored 3D card above the entity's
 * head, billboarded toward the camera. Same render pattern as
 * [com.canefe.storyclient.client.BubbleRenderer.renderRoundedBackground]
 * (QUADS via Tessellator + POSITION_COLOR shader, then text via
 * [TextRenderer.draw]).
 *
 * Layout:
 *   Recognized   → top: real name (bold, bright), bottom: descriptor (dim).
 *   Unrecognized → top: descriptor only.
 *
 * Triggered only for the entity under the player's crosshair, so we don't
 * have to worry about culling, distance fade, or render order.
 */
object HelixNametagRenderer {
    private const val MAX_DISTANCE_SQ = 64.0 * 64.0
    private const val SCALE = 0.006f

    /** Pixel offset of the card's left edge from the entity center, in screen pixels. */
    private const val SIDE_OFFSET_PX = 80f

    /** Wrap descriptor lines beyond this width (in font-pixel units, post-scale). */
    private const val MAX_TEXT_WIDTH = 150

    // Card colors (ARGB ints unpacked into floats below).
    private const val PANEL_BG = 0xE6101418.toInt()
    private const val PANEL_BORDER = 0xFF2A2F36.toInt()
    private const val STRIPE = 0xFFB04545.toInt()
    private const val NAME_COLOR = 0xFFEFEFEF.toInt()
    private const val DESCRIPTOR_COLOR = 0xFF9AA0A8.toInt()

    /** DM-only stat row color (HP / charId). Dimmer than descriptor. */
    private const val DM_STAT_COLOR = 0xFF6B7079.toInt()

    // Card geometry, in font-pixel units (since we already pre-scale by SCALE).
    private const val PAD_X = 5
    private const val PAD_Y = 3
    private const val STRIPE_W = 2
    private const val LINE_GAP = 1
    private const val BORDER = 1

    // Z layers — text closer to camera than border, border closer than bg.
    private const val Z_BG = 0.05f
    private const val Z_BORDER = 0.03f
    private const val Z_TEXT = 0.0f

    @Volatile var debug: Boolean = false
    private var lastLogMs: Long = 0L
    private fun log(msg: String) {
        if (!debug) return
        val now = System.currentTimeMillis()
        if (now - lastLogMs < 1000) return
        lastLogMs = now
        println("[HelixNametag] $msg")
    }

    fun render(context: WorldRenderContext) {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return
        val camera = context.camera() ?: return
        val matrices = context.matrixStack() ?: return
        val consumers = context.consumers() ?: return
        val font = client.textRenderer

        val rawTarget = client.crosshairTarget ?: return
        val target = rawTarget as? EntityHitResult ?: return
        if (target.type != HitResult.Type.ENTITY) return
        val entity = target.entity as? LivingEntity ?: return
        val entry = NearbyNPCCache.get(entity.uuid) ?: run {
            log("uuid=${entity.uuid} not in cache (size=${NearbyNPCCache.all().size})"); return
        }
        if (entity.squaredDistanceTo(player) > MAX_DISTANCE_SQ) return

        val realName = if (entry.characterId.isNotEmpty()) RecognitionCache.realNameOf(entry.characterId) else null
        val recognized = realName != null
        // DM omniscience: server only sends entry.realName when the perceiver
        // is a DM, so any non-empty value here means we're a DM looking at a
        // character we don't (in-fiction) recognize. Show "Stranger (RealName)"
        // so the DM knows who's who without breaking immersion for players.
        val dmRevealName = if (!recognized && entry.realName.isNotBlank()) entry.realName else null
        // Recognized: bold real name on top, descriptor below. DM-revealed:
        // "Stranger (RealName)" on top, descriptor below. Unrecognized non-DM:
        // descriptor (often a full sentence) becomes the only line — wrap it.
        val nameLine = (realName ?: dmRevealName?.let { "Stranger ($it)" }
            ?: entry.shortLabel.ifBlank { null }
            ?: entry.name).trim()
        val showNameOnTop = recognized || dmRevealName != null || entry.shortLabel.isNotBlank()
        val subLine =
            (if (showNameOnTop) entry.descriptor else "").trim()
        if (nameLine.isEmpty() && subLine.isEmpty()) return

        // Anchor at head height so the card reads alongside the face.
        val anchor = Vec3d(entity.x, entity.boundingBox.maxY - 0.05, entity.z)
        val cameraPos = camera.pos

        // Billboard math (mirrors BubbleRenderer).
        val diff = cameraPos.subtract(anchor)
        val yaw = -(atan2(diff.z, diff.x) + PI / 2.0)
        val horiz = sqrt(diff.x * diff.x + diff.z * diff.z)
        val pitch = atan2(diff.y, horiz)

        // DM stat line: shown when the server included DM-only data (i.e. realName
        // is non-empty). Format: "HP/MAX  abcd1234" — short charId for at-a-glance
        // disambiguation, full HP for combat/balance debugging.
        val dmStatLine: String? =
            if (entry.isDmView) {
                val hpPart = if (entry.hp >= 0 && entry.maxHp > 0) "${entry.hp}/${entry.maxHp}" else null
                val idPart = entry.characterId.takeIf { it.isNotEmpty() }?.take(8)
                listOfNotNull(hpPart, idPart).joinToString("  ").ifEmpty { null }
            } else {
                null
            }
        val dmStatText = dmStatLine?.let { Text.literal(it) }

        // Geometry sizing (post-scale "pixels"). Both lines wrap at MAX_TEXT_WIDTH.
        val showName = showNameOnTop && nameLine.isNotEmpty()
        val nameText = if (showName) Text.literal(nameLine).styled { it.withBold(true) } else null
        val nameW = nameText?.let { font.getWidth(it) } ?: 0
        val subLines: List<OrderedText> =
            if (subLine.isNotEmpty()) font.wrapLines(Text.literal(subLine), MAX_TEXT_WIDTH)
            else emptyList()
        val subW = subLines.maxOfOrNull { font.getWidth(it) } ?: 0
        val dmStatW = dmStatText?.let { font.getWidth(it) } ?: 0
        val contentW = maxOf(maxOf(nameW, subW), dmStatW)
        val nameH = if (showName) font.fontHeight else 0
        val gap = if (showName && subLines.isNotEmpty()) LINE_GAP else 0
        val subBlockH = subLines.size * font.fontHeight
        val dmGap = if (dmStatText != null && (showName || subLines.isNotEmpty())) LINE_GAP else 0
        val dmH = if (dmStatText != null) font.fontHeight else 0
        val contentH = nameH + gap + subBlockH + dmGap + dmH
        val cardW = contentW + STRIPE_W + PAD_X * 2
        val cardH = contentH + PAD_Y * 2

        matrices.push()
        matrices.translate(
            anchor.x - cameraPos.x,
            anchor.y - cameraPos.y,
            anchor.z - cameraPos.z,
        )
        matrices.multiply(Quaternionf().rotationY(yaw.toFloat()))
        matrices.multiply(Quaternionf().rotationX(pitch.toFloat()))
        matrices.scale(-SCALE, -SCALE, SCALE)

        // Position the card to the right of the entity in screen-space.
        // After the negative-X scale flip, "right" in post-scale coords is
        // actually negative X, so we put the left edge at -SIDE_OFFSET_PX -
        // cardW. Vertically center on the chest anchor.
        val cx = -SIDE_OFFSET_PX - cardW
        val cy = -cardH / 2f

        drawCard(matrices.peek().positionMatrix, cx, cy, cardW.toFloat(), cardH.toFloat())

        // Text — slight z offset toward camera so it draws on top of the card.
        val textX = cx + STRIPE_W + PAD_X
        var textY = cy + PAD_Y
        if (nameText != null) {
            drawText(font, nameText, textX, textY, NAME_COLOR, matrices.peek().positionMatrix, consumers)
            textY += nameH + gap
        }
        for (line in subLines) {
            drawOrderedText(font, line, textX, textY, DESCRIPTOR_COLOR, matrices.peek().positionMatrix, consumers)
            textY += font.fontHeight
        }
        if (dmStatText != null) {
            textY += dmGap
            drawText(font, dmStatText, textX, textY, DM_STAT_COLOR, matrices.peek().positionMatrix, consumers)
        }

        matrices.pop()
        log("drew '$nameLine' / '$subLine'${dmStatLine?.let { " / $it" } ?: ""}")
    }

    /** Card background + 1px border + left stripe, all as flat quads in the current matrix. */
    private fun drawCard(matrix: Matrix4f, x: Float, y: Float, w: Float, h: Float) {
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

        // Background card
        fill(x, y, x + w, y + h, PANEL_BG, Z_BG)
        // 1px border (top, bottom, left, right) at a layer in front of bg
        fill(x, y, x + w, y + BORDER, PANEL_BORDER, Z_BORDER)
        fill(x, y + h - BORDER, x + w, y + h, PANEL_BORDER, Z_BORDER)
        fill(x, y, x + BORDER, y + h, PANEL_BORDER, Z_BORDER)
        fill(x + w - BORDER, y, x + w, y + h, PANEL_BORDER, Z_BORDER)
        // Left stripe (inside the border)
        fill(x + BORDER, y + BORDER, x + BORDER + STRIPE_W, y + h - BORDER, STRIPE, Z_BORDER)

        BufferRenderer.drawWithGlobalProgram(buf.end())

        RenderSystem.disableBlend()
    }

    private fun drawText(
        font: TextRenderer,
        text: Text,
        x: Float,
        y: Float,
        color: Int,
        matrix: Matrix4f,
        consumers: VertexConsumerProvider,
    ) {
        font.draw(
            text, x, y, color, false,
            matrix, consumers,
            TextRenderer.TextLayerType.SEE_THROUGH,
            0,
            0xF000F0,
        )
    }

    private fun drawText(
        font: TextRenderer,
        text: Text,
        x: Float,
        y: Int,
        color: Int,
        matrix: Matrix4f,
        consumers: VertexConsumerProvider,
    ) = drawText(font, text, x, y.toFloat(), color, matrix, consumers)

    private fun drawOrderedText(
        font: TextRenderer,
        text: OrderedText,
        x: Float,
        y: Float,
        color: Int,
        matrix: Matrix4f,
        consumers: VertexConsumerProvider,
    ) {
        font.draw(
            text, x, y, color, false,
            matrix, consumers,
            TextRenderer.TextLayerType.SEE_THROUGH,
            0,
            0xF000F0,
        )
    }
}
