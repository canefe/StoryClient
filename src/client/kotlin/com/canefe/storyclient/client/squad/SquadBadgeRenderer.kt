package com.canefe.storyclient.client.squad

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.LivingEntity
import net.minecraft.text.Text
import net.minecraft.util.math.Box
import org.joml.Matrix4f
import java.util.ConcurrentModificationException
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Renders a small colored marker above the heads of NPCs that belong to a
 * known squad. The marker is the squad's stable color (`SquadListCache.Entry.color`)
 * and shows a chevron + the first letter of the squad name.
 *
 * Hidden when the player is not in command mode — the badges are a command
 * affordance, not a permanent identification overlay.
 */
object SquadBadgeRenderer {
    private const val RENDER_DISTANCE = 64.0
    private const val SCALE_FACTOR = 0.025f

    fun render(context: WorldRenderContext) {
        // Only show badges while the commander is actively in command mode.
        if (!SquadCommandState.commandMode) return

        val client = MinecraftClient.getInstance()
        val cameraEntity = client.cameraEntity ?: return
        val world = cameraEntity.world

        val matrices = context.matrixStack() ?: return
        val consumers = context.consumers() ?: return
        val cameraPos = context.camera().pos

        val entries = SquadListCache.entries
        if (entries.isEmpty()) return

        // Build a flat lookup: entityUuid -> Pair(squadEntry, isSelected)
        // Each NPC is tagged with the FIRST squad it appears in (matches cache index).
        val byEntityUuid = mutableMapOf<java.util.UUID, Pair<SquadListCache.Entry, Boolean>>()
        for (entry in entries) {
            val selected = SquadCommandState.selectedSquadIds.contains(entry.id)
            for (memberUuid in entry.memberUuids) {
                byEntityUuid.putIfAbsent(memberUuid, entry to selected)
            }
        }
        if (byEntityUuid.isEmpty()) return

        // Find the live entities matching cached member uuids. Bounding the
        // search keeps cost down even with many tracked NPCs in a busy area.
        val searchBox = Box.of(cameraEntity.pos, RENDER_DISTANCE * 2, RENDER_DISTANCE * 2, RENDER_DISTANCE * 2)
        val nearby =
            try {
                world.getOtherEntities(cameraEntity, searchBox) {
                    it is LivingEntity && byEntityUuid.containsKey(it.uuid)
                }
            } catch (_: ConcurrentModificationException) {
                emptyList()
            }

        if (nearby.isEmpty()) return

        val textRenderer = client.textRenderer

        for (entity in nearby) {
            if (entity !is LivingEntity) continue
            val (entry, selected) = byEntityUuid[entity.uuid] ?: continue
            if (entity.squaredDistanceTo(cameraEntity) > RENDER_DISTANCE * RENDER_DISTANCE) continue

            renderBadge(matrices, consumers, textRenderer, entity, entry, selected, cameraPos)
        }
    }

    private fun renderBadge(
        matrices: MatrixStack,
        consumers: VertexConsumerProvider,
        textRenderer: TextRenderer,
        entity: LivingEntity,
        entry: SquadListCache.Entry,
        selected: Boolean,
        cameraPos: net.minecraft.util.math.Vec3d,
    ) {
        // Anchor: just above the entity's nameplate (similar offset to vanilla scoreboard).
        val anchorY = entity.height + 0.7
        val anchorPos = entity.pos.add(0.0, anchorY, 0.0)

        // Billboard the badge to face the camera.
        val diff = cameraPos.subtract(anchorPos)
        val yaw = -(atan2(diff.z, diff.x) + PI / 2.0)
        val horiz = sqrt(diff.x * diff.x + diff.z * diff.z)
        val pitch = atan2(diff.y, horiz)

        matrices.push()
        matrices.translate(
            anchorPos.x - cameraPos.x,
            anchorPos.y - cameraPos.y,
            anchorPos.z - cameraPos.z,
        )
        matrices.multiply(org.joml.Quaternionf().rotationY(yaw.toFloat()))
        matrices.multiply(org.joml.Quaternionf().rotationX(-pitch.toFloat()))
        matrices.scale(-SCALE_FACTOR, -SCALE_FACTOR, SCALE_FACTOR)

        val matrix: Matrix4f = matrices.peek().positionMatrix
        val color = 0xFF000000.toInt() or entry.color
        val label = "▼ ${entry.name}"
        val textWidth = textRenderer.getWidth(label)
        val x = -textWidth / 2f
        val y = 0f

        // Background pill (slightly darker base + border in squad color)
        // Drawn via two text-renderer fills via the standard background-color path.
        val bgColor = if (selected) 0xCC222222.toInt() else 0x99111111.toInt()
        textRenderer.draw(
            Text.literal(label),
            x, y, color, false,
            matrix, consumers, TextRenderer.TextLayerType.SEE_THROUGH,
            bgColor, 15728880,
        )

        matrices.pop()
    }
}
