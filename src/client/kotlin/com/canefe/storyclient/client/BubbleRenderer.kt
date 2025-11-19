package com.canefe.storyclient.client

import com.mojang.blaze3d.systems.RenderSystem
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.render.*
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import org.joml.Quaternionf
import kotlin.math.*

object BubbleRenderer {
    private const val RENDER_DISTANCE = 32.0
    private const val BOX_WIDTH = 250
    private const val PADDING = 12
    private const val LINE_SPACING = 1.0f
    private const val SCALE_FACTOR = 0.02f

    private val activeBubbles = mutableMapOf<String, BubbleData>()

    data class BubbleData(
        val npcId: String,
        var entityId: Int?,
        var text: String,
        var color: String?,
        var isTyping: Boolean = true,
        var endTime: Long = 0L, // Time when bubble should be removed
        var shouldRemove: Boolean = false
    )

    fun startBubble(npcId: String, entityId: Int?, text: String, color: String? = null) {
        

        // Calculate removal time based on content
        val vanishTime = calculateVanishTime(text)
        val endTime = System.currentTimeMillis() + (vanishTime * 1000).toLong()

        activeBubbles[npcId] = BubbleData(
            npcId,
            entityId,
            text,
            color,
            isTyping = true,
            endTime = endTime,
            shouldRemove = true  // Auto-remove after timer
        )

        
    }

    fun updateBubble(npcId: String, text: String, color: String? = null) {
        

        activeBubbles[npcId]?.let {
            it.text = text
            it.color = color

            // Reset the removal timer on update
            val vanishTime = calculateVanishTime(text)
            it.endTime = System.currentTimeMillis() + (vanishTime * 1000).toLong()
            it.shouldRemove = true

            
        }
    }

    private fun calculateVanishTime(text: String): Double {
        // Calculate vanish time based on actual content length (not whitespace)
        val actualContent = text
            .lines()
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()

        val baseVanishTime = StoryClientConfig.messageVanishTime
        val contentLength = actualContent.length

        // Scale vanish time: min 3s, max 15s, based on content length
        return when {
            contentLength < 50 -> maxOf(3.0, baseVanishTime * 0.6)
            contentLength > 200 -> minOf(15.0, baseVanishTime * 1.5)
            else -> baseVanishTime
        }
    }

    fun endBubble(npcId: String) {
        // This is called when the conversation ends (<npc_typing_end>)
        // The bubble already has a timer set, so we just mark it as no longer typing
        

        activeBubbles[npcId]?.let { bubble ->
            bubble.isTyping = false
            // Timer is already set from startBubble/updateBubble
            
        }
    }

    fun removeBubble(npcId: String) {
        activeBubbles.remove(npcId)
    }

    fun removeAllBubbles() {
        activeBubbles.clear()
    }

    fun render(context: WorldRenderContext) {
        if (!StoryClientConfig.modEnabled) return

        val camera = context.camera()
        val cameraEntity = camera.focusedEntity ?: return
        val world = cameraEntity.world
        val cameraPos = camera.pos

        // Get required context objects with explicit null checks
        val matrices = context.matrixStack()
        val consumers = context.consumers()

        if (matrices == null || consumers == null) return

        // Get tick delta from context
        val tickDelta = context.tickCounter()?.getTickDelta(false) ?: 1.0f

        // Clean up expired bubbles
        val currentTime = System.currentTimeMillis()
        val bubblesToRemove = activeBubbles.filter { (npcId, bubble) ->
            val shouldRemove = bubble.shouldRemove && currentTime >= bubble.endTime
            if (bubble.shouldRemove) {
                
            }
            shouldRemove
        }.keys.toList()

        if (bubblesToRemove.isNotEmpty()) {
            
        }

        bubblesToRemove.forEach { npcId ->
            activeBubbles.remove(npcId)
            
        }

        // Iterate through all active bubbles
        activeBubbles.values.forEach { bubbleData ->
            // Try to find the entity
            val entity = findEntity(world, bubbleData, cameraEntity)

            if (entity != null && entity.squaredDistanceTo(cameraEntity) <= RENDER_DISTANCE * RENDER_DISTANCE) {
                renderBubble(matrices, consumers, entity, bubbleData, cameraPos, tickDelta)
            }
        }
    }

    private fun findEntity(world: net.minecraft.world.World, bubbleData: BubbleData, cameraEntity: Entity): Entity? {
        // First try by entity ID if we have it
        bubbleData.entityId?.let { id ->
            world.getEntityById(id)?.let { return it }
        }

        // Fallback: search for entities with matching UUID or name
        // This is a simplified approach - you may need to enhance this based on your server protocol
        val searchBox = Box.of(cameraEntity.pos, RENDER_DISTANCE * 2, RENDER_DISTANCE * 2, RENDER_DISTANCE * 2)
        val nearbyEntities = world.getOtherEntities(null, searchBox) {
            it is LivingEntity
        }

        // Try to match by UUID string in the npcId
        return nearbyEntities.firstOrNull { it.uuidAsString == bubbleData.npcId }
    }

    private fun renderBubble(
        matrices: MatrixStack,
        consumers: VertexConsumerProvider,
        entity: Entity,
        bubbleData: BubbleData,
        cameraPos: Vec3d,
        tickDelta: Float
    ) {
        matrices.push()

        // Get entity position with interpolation
        val entityPos = getInterpolatedPosition(entity, tickDelta)
        val entityHeight = entity.height
        val paddingAboveEntity = 0.4

        // Position bubble above entity
        val bubblePos = entityPos.add(0.0, entityHeight.toDouble() + paddingAboveEntity, 0.0)

        // Translate to bubble position relative to camera
        matrices.translate(
            bubblePos.x - cameraPos.x,
            bubblePos.y - cameraPos.y,
            bubblePos.z - cameraPos.z
        )

        // Calculate rotation to face camera
        val difference = cameraPos.subtract(bubblePos)
        val yaw = -(atan2(difference.z, difference.x) + PI / 2.0)
        val horizontalDistance = sqrt(difference.x * difference.x + difference.z * difference.z)
        val pitch = atan2(difference.y, horizontalDistance)

        // Apply yaw rotation
        val yawQuat = Quaternionf().rotationY(yaw.toFloat())
        matrices.multiply(yawQuat)

        // Apply pitch rotation
        val pitchQuat = Quaternionf().rotationX(pitch.toFloat())
        matrices.multiply(pitchQuat)

        // Scale based on config
        val scale = SCALE_FACTOR * StoryClientConfig.dialogueScale.toFloat()
        matrices.scale(-scale, -scale, scale)

        // Parse and prepare text
        val parsedData = parseDialogue(bubbleData.text)
        val textRenderer = MinecraftClient.getInstance().textRenderer

        // Build formatted text
        val formattedText = buildFormattedText(parsedData.body)
        val wrappedLines = textRenderer.wrapLines(formattedText, BOX_WIDTH - PADDING * 2)

        // Calculate dimensions
        //val widest = wrappedLines.maxOfOrNull { textRenderer.getWidth(it) } ?: 0
        val widest = 200
        val boxWidth = max(BOX_WIDTH, widest + PADDING * 2)
        val lineHeight = textRenderer.fontHeight
        val boxHeight = max(50, PADDING * 2 + (wrappedLines.size * lineHeight))

        // Center the box
        val x = -boxWidth / 2
        val y = -boxHeight - 10 // Offset above entity

        // Move text box up
        matrices.translate(0f, y.toFloat(), 0f)

        // Render the bubble
        renderBubbleBackground(matrices, boxWidth, boxHeight, bubbleData.color)

        // Render NPC name
        if (parsedData.name.isNotEmpty()) {
            renderNPCName(matrices, consumers, textRenderer, parsedData.name, parsedData.avatar, x, -15, bubbleData.color)
        }

        // Render text
        renderText(matrices, consumers, textRenderer, wrappedLines, x, 0, boxWidth, boxHeight)

        matrices.pop()
    }

    private fun getInterpolatedPosition(entity: Entity, tickDelta: Float): Vec3d {
        val prevX = entity.prevX
        val prevY = entity.prevY
        val prevZ = entity.prevZ
        val x = prevX + (entity.x - prevX) * tickDelta
        val y = prevY + (entity.y - prevY) * tickDelta
        val z = prevZ + (entity.z - prevZ) * tickDelta
        return Vec3d(x, y, z)
    }

    private data class ParsedDialogue(
        val name: String,
        val avatar: String?,
        val body: String
    )

    private fun parseDialogue(text: String): ParsedDialogue {
        val lines = text.lines()
        val nameIdx = lines.indexOfFirst { it.isNotBlank() }

        if (nameIdx >= 0 && nameIdx + 1 < lines.size) {
            val rawName = lines[nameIdx]

            // Check for avatar (non-alphanumeric characters at the beginning)
            val namePattern = Regex("([^\\p{L}\\p{N}\\s.,!?'-]+)(\\s*.*)")
            val match = namePattern.find(rawName)

            val avatar: String?
            val name: String

            if (match != null && match.groups.size > 2) {
                avatar = match.groups[1]?.value
                name = match.groups[2]?.value
                    ?.replace(Regex("<[^>]+>"), "")
                    ?.trim() ?: ""
            } else {
                avatar = null
                name = rawName.replace(Regex("<[^>]+>"), "").trim()
            }

            val body = lines.drop(nameIdx + 1)
                .takeWhile { it.isNotBlank() }
                .joinToString("\n")

            return ParsedDialogue(name, avatar, body)
        }

        return ParsedDialogue("", null, text)
    }

    private fun buildFormattedText(rawText: String): Text {
        // Strip leading spaces on each line and join with spaces
        val cleanedRaw = rawText.lineSequence()
            .map { it.trimStart() }
            .joinToString(" ")

        // Tokenize on '*' for italic sections (actions)
        var formatted = Text.empty()
        var buffer = StringBuilder()
        var italicMode = false

        for (ch in cleanedRaw) {
            if (ch == '*') {
                // Flush current buffer
                if (buffer.isNotEmpty()) {
                    if (italicMode) {
                        val core = buffer.toString().trim()
                        val inside = if (core.startsWith("(") && core.endsWith(")")) core else "($core)"
                        formatted = formatted.append(
                            Text.literal(inside)
                                .styled { it.withItalic(true).withColor(Formatting.DARK_GRAY) }
                        )
                        formatted = formatted.append(Text.literal("\n"))
                    } else {
                        formatted = formatted.append(Text.literal(buffer.toString()))
                    }
                }
                buffer = StringBuilder()
                italicMode = !italicMode
            } else {
                buffer.append(ch)
            }
        }

        // Final flush
        if (buffer.isNotEmpty()) {
            if (italicMode) {
                val core = buffer.toString().trim()
                val inside = if (core.startsWith("(") && core.endsWith(")")) core else "($core)"
                formatted = formatted.append(
                    Text.literal(inside)
                        .styled { it.withItalic(true).withColor(Formatting.DARK_GRAY) }
                )
            } else {
                formatted = formatted.append(Text.literal(buffer.toString()))
            }
        }

        return formatted
    }

    private fun renderBubbleBackground(matrices: MatrixStack, boxWidth: Int, boxHeight: Int, color: String?) {
        val matrix = matrices.peek().positionMatrix

        // Set up rendering with proper depth testing
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(true)
        RenderSystem.setShader { GameRenderer.getPositionColorProgram() }

        val tessellator = Tessellator.getInstance()
        val bufferBuilder = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR)

        // Background color
        val bgColor = 0xfbc170
        val bgR = ((bgColor shr 16) and 0xFF) / 255f
        val bgG = ((bgColor shr 8) and 0xFF) / 255f
        val bgB = (bgColor and 0xFF) / 255f

        // Border color
        val borderColor = 0xB86A2F
        val borderR = ((borderColor shr 16) and 0xFF) / 255f
        val borderG = ((borderColor shr 8) and 0xFF) / 255f
        val borderB = (borderColor and 0xFF) / 255f

        val x = -boxWidth / 2f
        val y = 0f
        val z = 0.01f  // Use positive Z for proper depth testing

        // Main background
        bufferBuilder.vertex(matrix, x, y + boxHeight, z).color(bgR, bgG, bgB, 1f)
        bufferBuilder.vertex(matrix, x + boxWidth, y + boxHeight, z).color(bgR, bgG, bgB, 1f)
        bufferBuilder.vertex(matrix, x + boxWidth, y, z).color(bgR, bgG, bgB, 1f)
        bufferBuilder.vertex(matrix, x, y, z).color(bgR, bgG, bgB, 1f)

        // Top border
        bufferBuilder.vertex(matrix, x, y + 3, z).color(borderR, borderG, borderB, 1f)
        bufferBuilder.vertex(matrix, x + boxWidth, y + 3, z).color(borderR, borderG, borderB, 1f)
        bufferBuilder.vertex(matrix, x + boxWidth, y, z).color(borderR, borderG, borderB, 1f)
        bufferBuilder.vertex(matrix, x, y, z).color(borderR, borderG, borderB, 1f)

        // Bottom border
        bufferBuilder.vertex(matrix, x, y + boxHeight, z).color(borderR, borderG, borderB, 1f)
        bufferBuilder.vertex(matrix, x + boxWidth, y + boxHeight, z).color(borderR, borderG, borderB, 1f)
        bufferBuilder.vertex(matrix, x + boxWidth, y + boxHeight - 3, z).color(borderR, borderG, borderB, 1f)
        bufferBuilder.vertex(matrix, x, y + boxHeight - 3, z).color(borderR, borderG, borderB, 1f)

        // Left border
        bufferBuilder.vertex(matrix, x, y + boxHeight, z).color(borderR, borderG, borderB, 1f)
        bufferBuilder.vertex(matrix, x + 3, y + boxHeight, z).color(borderR, borderG, borderB, 1f)
        bufferBuilder.vertex(matrix, x + 3, y, z).color(borderR, borderG, borderB, 1f)
        bufferBuilder.vertex(matrix, x, y, z).color(borderR, borderG, borderB, 1f)

        // Right border
        bufferBuilder.vertex(matrix, x + boxWidth - 3, y + boxHeight, z).color(borderR, borderG, borderB, 1f)
        bufferBuilder.vertex(matrix, x + boxWidth, y + boxHeight, z).color(borderR, borderG, borderB, 1f)
        bufferBuilder.vertex(matrix, x + boxWidth, y, z).color(borderR, borderG, borderB, 1f)
        bufferBuilder.vertex(matrix, x + boxWidth - 3, y, z).color(borderR, borderG, borderB, 1f)

        BufferRenderer.drawWithGlobalProgram(bufferBuilder.end())

        RenderSystem.disableBlend()
        RenderSystem.disableDepthTest()
    }

    private fun renderNPCName(
        matrices: MatrixStack,
        consumers: VertexConsumerProvider,
        textRenderer: TextRenderer,
        name: String,
        avatar: String?,
        x: Int,
        y: Int,
        color: String?
    ) {
        val nameText = Text.literal(name).styled { it.withBold(true) }
        val nameColorValue = color?.let { if (it.startsWith("#")) it else "#$it" } ?: "#FFCC44"
        val colorRgb = nameColorValue.removePrefix("#").trim().toIntOrNull(16) ?: 0xFFCC44
        val colorWithAlpha = 0xFF000000.toInt() or colorRgb

        // Render avatar if present
        if (avatar != null) {
            val avatarText = Text.literal(avatar)
            val avatarWidth = textRenderer.getWidth(avatarText)
            val avatarHeight = 42

            // Position calculations
            val avatarX = x + 2
            val avatarY = y - avatarHeight - 5

            // Frame dimensions
            val frameX = avatarX - 2
            val frameY = avatarY - 4
            val frameWidth = avatarWidth + 4
            val frameHeight = avatarHeight + 8

            // Render decorative frame corners
            renderAvatarFrame(matrices, frameX, frameY, frameWidth, frameHeight, colorRgb)

            // Draw the avatar text
            textRenderer.draw(
                avatarText,
                avatarX.toFloat(),
                avatarY.toFloat(),
                0xFFFFFFFF.toInt(),
                false,
                matrices.peek().positionMatrix,
                consumers,
                TextRenderer.TextLayerType.NORMAL,
                0,
                15728880
            )
        }

        // Render name
        textRenderer.draw(
            nameText,
            x.toFloat(),
            y.toFloat(),
            colorWithAlpha,
            false,
            matrices.peek().positionMatrix,
            consumers,
            TextRenderer.TextLayerType.NORMAL,
            0,
            15728880
        )
    }

    private fun renderAvatarFrame(
        matrices: MatrixStack,
        frameX: Int,
        frameY: Int,
        frameWidth: Int,
        frameHeight: Int,
        colorRgb: Int
    ) {
        val matrix = matrices.peek().positionMatrix

        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(true)
        RenderSystem.setShader { GameRenderer.getPositionColorProgram() }

        val tessellator = Tessellator.getInstance()
        val bufferBuilder = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR)

        // Extract RGB components
        val r = ((colorRgb shr 16) and 0xFF) / 255f
        val g = ((colorRgb shr 8) and 0xFF) / 255f
        val b = (colorRgb and 0xFF) / 255f
        val a = 1f

        val z = 0.01f

        // Helper function to draw a filled rectangle
        fun fillRect(x1: Int, y1: Int, x2: Int, y2: Int) {
            val fx1 = x1.toFloat()
            val fy1 = y1.toFloat()
            val fx2 = x2.toFloat()
            val fy2 = y2.toFloat()

            bufferBuilder.vertex(matrix, fx1, fy2, z).color(r, g, b, a)
            bufferBuilder.vertex(matrix, fx2, fy2, z).color(r, g, b, a)
            bufferBuilder.vertex(matrix, fx2, fy1, z).color(r, g, b, a)
            bufferBuilder.vertex(matrix, fx1, fy1, z).color(r, g, b, a)
        }

        // Top left corner
        fillRect(frameX, frameY, frameX + 2, frameY + 2)
        fillRect(frameX + 2, frameY, frameX + 4, frameY + 1)
        fillRect(frameX, frameY + 2, frameX + 1, frameY + 4)

        // Top right corner
        fillRect(frameX + frameWidth - 2, frameY, frameX + frameWidth, frameY + 2)
        fillRect(frameX + frameWidth - 4, frameY, frameX + frameWidth - 2, frameY + 1)
        fillRect(frameX + frameWidth - 1, frameY + 2, frameX + frameWidth, frameY + 4)

        // Bottom left corner
        fillRect(frameX, frameY + frameHeight - 2, frameX + 2, frameY + frameHeight)
        fillRect(frameX, frameY + frameHeight - 4, frameX + 1, frameY + frameHeight - 2)
        fillRect(frameX + 2, frameY + frameHeight - 1, frameX + 4, frameY + frameHeight)

        // Bottom right corner
        fillRect(frameX + frameWidth - 2, frameY + frameHeight - 2, frameX + frameWidth, frameY + frameHeight)
        fillRect(frameX + frameWidth - 1, frameY + frameHeight - 4, frameX + frameWidth, frameY + frameHeight - 2)
        fillRect(frameX + frameWidth - 4, frameY + frameHeight - 1, frameX + frameWidth - 2, frameY + frameHeight)

        BufferRenderer.drawWithGlobalProgram(bufferBuilder.end())

        RenderSystem.disableBlend()
        RenderSystem.disableDepthTest()
    }

    private fun renderText(
        matrices: MatrixStack,
        consumers: VertexConsumerProvider,
        textRenderer: TextRenderer,
        wrappedLines: List<net.minecraft.text.OrderedText>,
        x: Int,
        y: Int,
        boxWidth: Int,
        boxHeight: Int
    ) {
        val textColor = 0x52130A
        val fullBright = 15728880

        var textY = y + PADDING
        val textX = x + PADDING

        wrappedLines.forEach { line ->
            if (textY <= y + boxHeight - PADDING) {
                textRenderer.draw(
                    line,
                    textX.toFloat(),
                    textY.toFloat(),
                    textColor,
                    false,
                    matrices.peek().positionMatrix,
                    consumers,
                    TextRenderer.TextLayerType.NORMAL,
                    0,
                    fullBright
                )
                textY += textRenderer.fontHeight
            }
        }
    }
}
