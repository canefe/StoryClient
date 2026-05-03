package com.canefe.storyclient.client.puppet

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
 * Renders a ghost marker at the block the player is looking at while in puppet
 * mode, mirroring [com.canefe.storyclient.client.squad.SquadFormationPreviewRenderer].
 */
object PuppetCursorRenderer {
    private const val MAX_RAY = 50.0
    private const val SCALE_FACTOR = 0.025f
    private const val COLOR = 0xFF3333

    fun render(context: WorldRenderContext) {
        if (!PuppetState.inPuppetMode) return

        val client = MinecraftClient.getInstance()
        val world = client.world ?: return
        val cameraEntity = client.cameraEntity ?: return

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
                return
            }

        val matrices = context.matrixStack() ?: return
        val consumers = context.consumers() ?: return
        val cameraPos = context.camera().pos
        renderMarker(matrices, consumers, client.textRenderer, anchor, COLOR, cameraPos)
    }

    private fun renderMarker(
        matrices: MatrixStack,
        consumers: VertexConsumerProvider,
        textRenderer: TextRenderer,
        worldPos: Vec3d,
        color: Int,
        cameraPos: Vec3d,
    ) {
        val diff = cameraPos.subtract(worldPos)
        val yaw = -(atan2(diff.z, diff.x) + PI / 2.0)
        val horiz = sqrt(diff.x * diff.x + diff.z * diff.z)
        val pitch = atan2(diff.y, horiz)

        matrices.push()
        matrices.translate(
            worldPos.x - cameraPos.x,
            worldPos.y - cameraPos.y + 0.05,
            worldPos.z - cameraPos.z,
        )
        matrices.multiply(org.joml.Quaternionf().rotationY(yaw.toFloat()))
        matrices.multiply(org.joml.Quaternionf().rotationX(-pitch.toFloat()))
        matrices.scale(-SCALE_FACTOR, -SCALE_FACTOR, SCALE_FACTOR)

        val matrix: Matrix4f = matrices.peek().positionMatrix
        val tinted = 0xCC000000.toInt() or color
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
