package com.canefe.storyclient.client.squad

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.text.Text
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Renders ghost markers at each computed formation slot for currently selected
 * squads, anchored at where the player is looking.
 *
 * Visible only when:
 *   - Command mode is on
 *   - At least one squad is selected
 *   - The crosshair raycast hits a block within MAX_RAY
 *
 * Each selected squad's preview uses its own color and member count. Multiple
 * selected squads stack at the same anchor — visually shows the joint
 * formation that an order would produce.
 */
object SquadFormationPreviewRenderer {
    private const val MAX_RAY = 50.0
    private const val SCALE_FACTOR = 0.025f

    fun render(context: WorldRenderContext) {
        if (!SquadCommandState.commandMode) return
        if (SquadCommandState.selectedSquadIds.isEmpty()) return

        val client = MinecraftClient.getInstance()
        val player = client.player ?: return
        val world = client.world ?: return
        val cameraEntity = client.cameraEntity ?: return

        // Crosshair raycast — match the puppet/squad MOVE_TO logic.
        val start = cameraEntity.eyePos
        val direction = cameraEntity.getRotationVec(1.0f)
        val end = start.add(direction.multiply(MAX_RAY))
        val hit =
            world.raycast(
                net.minecraft.world.RaycastContext(
                    start, end,
                    net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
                    net.minecraft.world.RaycastContext.FluidHandling.NONE,
                    cameraEntity,
                ),
            )
        val anchor: Vec3d =
            if (hit.type == HitResult.Type.BLOCK) {
                val pos: BlockPos = (hit as BlockHitResult).blockPos
                Vec3d(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
            } else {
                return // Air-only previews would be visually noisy; require a block target.
            }

        val matrices = context.matrixStack() ?: return
        val consumers = context.consumers() ?: return
        val cameraPos = context.camera().pos
        val textRenderer = client.textRenderer

        // Render each selected squad's slot preview at the anchor.
        // Multiple selections overlap intentionally — that's the actual joint outcome.
        for (squadId in SquadCommandState.selectedSquadIds) {
            val entry = SquadListCache.byId(squadId) ?: continue
            if (entry.memberCount <= 0) continue

            val formation = formationFromLabel(entry.formationLabel)
            val offsets =
                ClientFormationCalculator.slotOffsets(
                    facingYawDeg = player.yaw,
                    memberCount = entry.memberCount,
                    formation = formation,
                )
            for (offset in offsets) {
                val slotPos = anchor.add(offset.first, 0.0, offset.third)
                renderMarker(matrices, consumers, textRenderer, slotPos, entry.color, cameraPos)
            }
        }
    }

    private fun renderMarker(
        matrices: MatrixStack,
        consumers: VertexConsumerProvider,
        textRenderer: TextRenderer,
        worldPos: Vec3d,
        color: Int,
        cameraPos: Vec3d,
    ) {
        // Billboard the marker so it always faces the camera.
        val diff = cameraPos.subtract(worldPos)
        val yaw = -(atan2(diff.z, diff.x) + PI / 2.0)
        val horiz = sqrt(diff.x * diff.x + diff.z * diff.z)
        val pitch = atan2(diff.y, horiz)

        matrices.push()
        matrices.translate(
            worldPos.x - cameraPos.x,
            worldPos.y - cameraPos.y + 0.05, // hover just above ground
            worldPos.z - cameraPos.z,
        )
        matrices.multiply(org.joml.Quaternionf().rotationY(yaw.toFloat()))
        matrices.multiply(org.joml.Quaternionf().rotationX(-pitch.toFloat()))
        matrices.scale(-SCALE_FACTOR, -SCALE_FACTOR, SCALE_FACTOR)

        val matrix: Matrix4f = matrices.peek().positionMatrix
        val tinted = 0xCC000000.toInt() or color // alpha 0xCC for "ghost" feel
        val label = "▼"
        val textWidth = textRenderer.getWidth(label)
        val x = -textWidth / 2f
        val y = 0f

        textRenderer.draw(
            Text.literal(label),
            x, y, tinted, false,
            matrix, consumers, TextRenderer.TextLayerType.SEE_THROUGH,
            0, 15728880,
        )

        matrices.pop()
    }
}
