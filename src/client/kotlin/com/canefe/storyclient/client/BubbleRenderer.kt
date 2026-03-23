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
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

object BubbleRenderer {
    private const val RENDER_DISTANCE = 32.0
    private const val BOX_WIDTH = 195
    private const val PADDING = 12
    private const val LINE_SPACING = 1.0f
    private const val SCALE_FACTOR = 0.02f

    private const val FADE_IN_MS = 300L
    private const val FADE_OUT_MS = 500L

    // ── Data model: separate queues for dialogue and actions ─────────

    data class DialogueEntry(
        var text: String,
        var color: String?,
        var startTime: Long = System.currentTimeMillis(),
        var endTime: Long = 0L,
        var shouldRemove: Boolean = false,
    )

    data class ActionEntry(
        var text: String,
        var color: String?,
        var startTime: Long = System.currentTimeMillis(),
        var endTime: Long = 0L,
        var shouldRemove: Boolean = false,
        var lastActionText: String? = null,
    )

    data class NPCBubbleState(
        val npcId: String,
        var entityId: Int?,
        var dialogue: DialogueEntry? = null,
        var action: ActionEntry? = null,
        var parsedName: String = "",
        var parsedAvatar: String? = null,
    )

    private val npcStates = ConcurrentHashMap<String, NPCBubbleState>()

    // ── Public API ──────────────────────────────────────────────────

    fun startBubble(npcId: String, entityId: Int?, text: String, color: String? = null) {
        val state = npcStates.getOrPut(npcId) { NPCBubbleState(npcId, entityId) }
        state.entityId = entityId ?: state.entityId

        val parsed = parseDialogue(text)
        state.parsedName = parsed.name
        state.parsedAvatar = parsed.avatar
        routeToQueues(state, parsed.body, color)
    }

    fun updateBubble(npcId: String, text: String, color: String? = null) {
        val state = npcStates[npcId] ?: run {
            startBubble(npcId, null, text, color)
            return
        }

        val parsed = parseDialogue(text)
        if (parsed.name.isNotEmpty()) state.parsedName = parsed.name
        if (parsed.avatar != null) state.parsedAvatar = parsed.avatar
        routeToQueues(state, parsed.body, color)
    }

    fun endBubble(npcId: String) {
        val state = npcStates[npcId] ?: return
        state.dialogue?.shouldRemove = true
        state.action?.shouldRemove = true
    }

    fun removeBubble(npcId: String) {
        npcStates.remove(npcId)
    }

    fun removeAllBubbles() {
        npcStates.clear()
    }

    private fun routeToQueues(state: NPCBubbleState, body: String, color: String?) {
        val now = System.currentTimeMillis()
        val dialogueText = stripActions(body)
        val actionText = if (hasActions(body)) extractActionText(body) else null

        // Update dialogue queue
        if (dialogueText.isNotEmpty()) {
            val vanishTime = calculateVanishTime(dialogueText)
            val existing = state.dialogue
            if (existing != null) {
                existing.text = dialogueText
                existing.color = color
                existing.endTime = now + (vanishTime * 1000).toLong()
                existing.shouldRemove = true
            } else {
                state.dialogue = DialogueEntry(
                    text = dialogueText,
                    color = color,
                    startTime = now,
                    endTime = now + (vanishTime * 1000).toLong(),
                    shouldRemove = true,
                )
            }
        }

        // Update action queue
        if (actionText != null) {
            val vanishTime = calculateVanishTime(actionText)
            val existing = state.action
            if (existing != null && existing.lastActionText != actionText) {
                // New action text — reset animation
                existing.text = actionText
                existing.color = color
                existing.startTime = now
                existing.endTime = now + (vanishTime * 1000).toLong()
                existing.shouldRemove = true
                existing.lastActionText = actionText
            } else if (existing == null) {
                state.action = ActionEntry(
                    text = actionText,
                    color = color,
                    startTime = now,
                    endTime = now + (vanishTime * 1000).toLong(),
                    shouldRemove = true,
                    lastActionText = actionText,
                )
            }
        }
    }

    private fun calculateVanishTime(text: String): Double {
        val actualContent = text.lines().joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ").trim()
        val baseVanishTime = StoryClientConfig.messageVanishTime
        return when {
            actualContent.length < 50 -> maxOf(3.0, baseVanishTime * 0.6)
            actualContent.length > 200 -> minOf(15.0, baseVanishTime * 1.5)
            else -> baseVanishTime
        }
    }

    private fun calculateAlpha(startTime: Long, endTime: Long, shouldRemove: Boolean): Float {
        val now = System.currentTimeMillis()
        val elapsed = now - startTime
        if (elapsed < FADE_IN_MS) {
            return (elapsed.toFloat() / FADE_IN_MS).coerceIn(0f, 1f)
        }
        if (shouldRemove && endTime > 0) {
            val remaining = endTime - now
            if (remaining < FADE_OUT_MS) {
                return (remaining.toFloat() / FADE_OUT_MS).coerceIn(0f, 1f)
            }
        }
        return 1f
    }

    // ── Render ───────────────────────────────────────────────────────

    fun render(context: WorldRenderContext) {
        if (!StoryClientConfig.modEnabled) return

        try {
            val camera = context.camera()
            val cameraEntity = camera.focusedEntity ?: return
            val world = cameraEntity.world
            val cameraPos = camera.pos
            val matrices = context.matrixStack() ?: return
            val consumers = context.consumers() ?: return
            val tickDelta = context.tickCounter()?.getTickDelta(false) ?: 1.0f
            val currentTime = System.currentTimeMillis()

            // Cleanup expired entries
            val snapshot = npcStates.entries.toList()
            snapshot.forEach { (npcId, state) ->
                val dialogueExpired = state.dialogue?.let { it.shouldRemove && currentTime >= it.endTime } ?: true
                val actionExpired = state.action?.let { it.shouldRemove && currentTime >= it.endTime } ?: true
                if (dialogueExpired) state.dialogue = null
                if (actionExpired) state.action = null
                if (state.dialogue == null && state.action == null) {
                    npcStates.remove(npcId)
                }
            }

            // Render each NPC's bubbles
            npcStates.values.toList().forEach { state ->
                val entity = findEntity(world, state, cameraEntity) ?: return@forEach
                if (entity.squaredDistanceTo(cameraEntity) > RENDER_DISTANCE * RENDER_DISTANCE) return@forEach

                // Render action text (scattered around body)
                state.action?.let { action ->
                    renderScatteredActions(matrices, consumers, entity, state.npcId, action, cameraPos, tickDelta)
                }

                // Render dialogue textbox (above head)
                state.dialogue?.let { dialogue ->
                    renderDialogueBubble(matrices, consumers, entity, state, dialogue, cameraPos, tickDelta)
                }
            }
        } catch (_: ConcurrentModificationException) {
        } catch (_: Exception) {
        }
    }

    private fun findEntity(world: net.minecraft.world.World, state: NPCBubbleState, cameraEntity: Entity): Entity? {
        state.entityId?.let { id ->
            world.getEntityById(id)?.let { return it }
        }
        return try {
            val searchBox = Box.of(cameraEntity.pos, RENDER_DISTANCE * 2, RENDER_DISTANCE * 2, RENDER_DISTANCE * 2)
            val found = world.getOtherEntities(null, searchBox) {
                it is LivingEntity && it.uuidAsString == state.npcId
            }.firstOrNull()
            found?.let { state.entityId = it.id }
            found
        } catch (_: ConcurrentModificationException) {
            null
        }
    }

    /**
     * Returns true if the body has a trailing unclosed action (odd number of asterisks).
     */
    private fun hasPendingAction(body: String): Boolean {
        return body.trim().count { it == '*' } % 2 != 0
    }

    /**
     * Checks if the message body is purely an action (only asterisk-wrapped text, no dialogue).
     * Also treats a trailing unclosed *text as a pending action.
     */
    private fun isActionOnly(body: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return false
        var stripped = trimmed.replace(Regex("\\*[^*]+\\*"), "") // completed actions
        if (hasPendingAction(trimmed)) {
            stripped = stripped.replace(Regex("\\*[^*]*$"), "")  // trailing unclosed action
        }
        return stripped.trim().isEmpty()
    }

    /**
     * Extracts action text from asterisk-wrapped content, including pending unclosed actions.
     */
    private fun extractActionText(body: String): String {
        val trimmed = body.trim()
        val completed = Regex("\\*([^*]+)\\*").findAll(trimmed)
            .map { it.groupValues[1].trim() }
            .toList()

        // Only treat trailing text as pending action if there's an odd number of asterisks
        val pending = if (hasPendingAction(trimmed)) {
            Regex("\\*([^*]+)$").find(trimmed)?.groupValues?.get(1)?.trim()
        } else null

        val all = if (pending != null) completed + pending else completed
        return all.joinToString(" ").ifEmpty { trimmed }
    }

    /**
     * Checks if the body contains any action text (completed or pending).
     */
    private fun hasActions(body: String): Boolean {
        val trimmed = body.trim()
        return Regex("\\*[^*]+\\*").containsMatchIn(trimmed) ||
               hasPendingAction(trimmed)
    }

    /**
     * Strips action text from body, leaving only dialogue.
     * Also strips trailing unclosed actions.
     */
    private fun stripActions(body: String): String {
        var stripped = body.replace(Regex("\\*[^*]+\\*"), "") // completed actions
        if (hasPendingAction(body)) {
            stripped = stripped.replace(Regex("\\*[^*]*$"), "") // trailing unclosed action
        }
        return stripped.trim()
    }

    /**
     * Splits text into 2-word groups.
     */
    private fun splitIntoWordGroups(text: String, wordsPerGroup: Int = 2): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.chunked(wordsPerGroup) { it.joinToString(" ") }
    }

    /**
     * Renders action-only text as scattered word groups floating around the NPC's head.
     * Each group is positioned at a different angle around the entity, with varying Y offset.
     */
    private fun renderScatteredActions(
        matrices: MatrixStack,
        consumers: VertexConsumerProvider,
        entity: Entity,
        npcId: String,
        action: ActionEntry,
        cameraPos: Vec3d,
        tickDelta: Float
    ) {
        val wordGroups = splitIntoWordGroups(action.text)
        if (wordGroups.isEmpty()) return

        val textRenderer = MinecraftClient.getInstance().textRenderer
        val nameColorValue = action.color?.let { if (it.startsWith("#")) it else "#$it" } ?: "#FFCC44"
        val actionColorRgb = nameColorValue.removePrefix("#").trim().toIntOrNull(16) ?: 0xFFCC44
        val entityPos = getInterpolatedPosition(entity, tickDelta)
        val entityHeight = entity.height
        val actionScale = SCALE_FACTOR * StoryClientConfig.dialogueScale.toFloat() * 1.4f

        // Use a seed based on npcId so positions are stable per-NPC but vary between NPCs
        val seed = npcId.hashCode().toLong()
        val rng = Random(seed)

        // Anchor at the NPC's mid-body height
        val anchorPos = entityPos.add(0.0, (entityHeight * 0.5).toDouble(), 0.0)

        // Billboard direction: calculate once for consistent side placement
        val difference = cameraPos.subtract(anchorPos)
        val yaw = -(atan2(difference.z, difference.x) + PI / 2.0)
        val horizontalDistance = sqrt(difference.x * difference.x + difference.z * difference.z)
        val pitch = atan2(difference.y, horizontalDistance)

        // Pre-generate random values so iteration order doesn't matter
        val tilts = wordGroups.indices.map { (rng.nextDouble() * 20.0 - 10.0).toFloat() }
        val yVariations = wordGroups.indices.map { (rng.nextDouble() * 8.0 - 4.0).toFloat() }

        // Staggered pop-up animation timing
        val now = System.currentTimeMillis()
        val staggerDelayMs = 350L  // Delay between each word group appearing
        val popDurationMs = 450L   // How long the scale-up animation takes

        // Alternate word groups between left and right side of the body
        for ((i, group) in wordGroups.withIndex()) {
            // Calculate animation progress for this group
            val groupStartTime = action.startTime + (i * staggerDelayMs)
            val elapsed = now - groupStartTime
            if (elapsed < 0) continue // Not yet visible

            // Scale easing: overshoot then settle (pop effect)
            val rawProgress = (elapsed.toFloat() / popDurationMs).coerceIn(0f, 1f)
            val popScale = if (rawProgress < 1f) {
                // Overshoot ease-out: goes to ~1.15 then settles to 1.0
                val t = rawProgress
                val overshoot = 1.15f
                1f - (1f - t) * (1f - t) * (1f - overshoot * t)
            } else {
                1f
            }

            if (popScale <= 0.01f) continue

            matrices.push()

            // Alternate sides: even indices go left, odd go right
            val side = if (i % 2 == 0) -1.0 else 1.0

            // Vertical spread: distribute groups along the body height, top-to-bottom reading order
            val yFraction = (i.toFloat() / maxOf(wordGroups.size - 1, 1)) // 0.0 to 1.0
            val yPos = entityHeight * (1.0 - yFraction * 0.7) // top ~100% down to ~30% of body height

            val groupPos = anchorPos.add(
                0.0,
                yPos.toDouble() - (entityHeight * 0.5).toDouble() + yVariations[i] * 0.02,
                0.0
            )

            // Translate to group position relative to camera
            matrices.translate(
                groupPos.x - cameraPos.x,
                groupPos.y - cameraPos.y,
                groupPos.z - cameraPos.z
            )

            // Billboard: face camera
            matrices.multiply(Quaternionf().rotationY(yaw.toFloat()))
            matrices.multiply(Quaternionf().rotationX(pitch.toFloat()))

            // Slight Z-rotation tilt per group
            val tilt = tilts[i]
            matrices.multiply(Quaternionf().rotationZ(Math.toRadians(tilt.toDouble()).toFloat()))

            // Apply pop scale animation
            val animatedScale = actionScale * popScale
            matrices.scale(-animatedScale, -animatedScale, animatedScale)

            val displayText = Text.literal(group)
                .styled { it.withItalic(true).withColor(actionColorRgb) }

            val textWidth = textRenderer.getWidth(displayText)

            // Position on the left or right side in screen-space
            val xPixelOffset = (side * (textWidth / 2f + 30f)).toFloat()

            // Fade in alpha alongside the scale
            val alphaInt = (rawProgress.coerceIn(0f, 1f) * 255).toInt()
            val textColor = (alphaInt shl 24) or actionColorRgb

            val textX = -textWidth / 2f + xPixelOffset
            val textY = yVariations[i]
            val bgAlphaInt = (rawProgress.coerceIn(0f, 1f) * 140).toInt()
            val bgColor = (bgAlphaInt shl 24)

            drawOutlinedText(
                textRenderer, displayText, textX, textY, textColor,
                matrices.peek().positionMatrix, consumers, bgColor,
            )

            matrices.pop()
        }
    }

    /**
     * Renders text with a white outline by drawing it offset in 4 directions, then the main text on top.
     */
    private fun drawOutlinedText(
        textRenderer: TextRenderer,
        text: Text,
        x: Float,
        y: Float,
        color: Int,
        matrix: Matrix4f,
        consumers: VertexConsumerProvider,
        bgColor: Int = 0,
        light: Int = 15728880,
    ) {
        val alpha = ((color ushr 24) and 0xFF)
        // Darken the text color by ~40% for the shadow
        val r = ((color ushr 16) and 0xFF) * 6 / 10
        val g = ((color ushr 8) and 0xFF) * 6 / 10
        val b = (color and 0xFF) * 6 / 10
        val shadowRgb = (r shl 16) or (g shl 8) or b
        val shadowColor = (alpha shl 24) or shadowRgb

        val shadowText = Text.literal(text.string).styled {
            it.withColor(shadowRgb).withBold(text.style.isBold).withItalic(text.style.isItalic)
        }

        // Draw shadow (offset down-right)
        textRenderer.draw(
            shadowText, x + 1f, y + 1f, shadowColor, false,
            matrix, consumers, TextRenderer.TextLayerType.SEE_THROUGH, 0, light,
        )
        // Draw main text
        textRenderer.draw(
            text, x, y, color, false,
            matrix, consumers, TextRenderer.TextLayerType.SEE_THROUGH, bgColor, light,
        )
    }

    private fun renderDialogueBubble(
        matrices: MatrixStack,
        consumers: VertexConsumerProvider,
        entity: Entity,
        state: NPCBubbleState,
        dialogue: DialogueEntry,
        cameraPos: Vec3d,
        tickDelta: Float
    ) {
        matrices.push()

        val entityPos = getInterpolatedPosition(entity, tickDelta)
        val entityHeight = entity.height
        val bubblePos = entityPos.add(0.0, entityHeight.toDouble() + 0.4, 0.0)

        matrices.translate(
            bubblePos.x - cameraPos.x,
            bubblePos.y - cameraPos.y,
            bubblePos.z - cameraPos.z
        )

        val difference = cameraPos.subtract(bubblePos)
        val yaw = -(atan2(difference.z, difference.x) + PI / 2.0)
        val horizontalDistance = sqrt(difference.x * difference.x + difference.z * difference.z)
        val pitch = atan2(difference.y, horizontalDistance)

        matrices.multiply(Quaternionf().rotationY(yaw.toFloat()))
        matrices.multiply(Quaternionf().rotationX(pitch.toFloat()))

        val scale = SCALE_FACTOR * StoryClientConfig.dialogueScale.toFloat()
        matrices.scale(-scale, -scale, scale)

        val textRenderer = MinecraftClient.getInstance().textRenderer
        val formattedText = buildFormattedText(dialogue.text)
        val wrappedLines = textRenderer.wrapLines(formattedText, BOX_WIDTH - PADDING * 2)

        val widest = wrappedLines.maxOfOrNull { textRenderer.getWidth(it) } ?: 0
        val boxWidth = max(BOX_WIDTH, widest + PADDING * 2)
        val lineHeight = textRenderer.fontHeight
        val boxHeight = max(50, PADDING * 2 + (wrappedLines.size * lineHeight))

        val x = -boxWidth / 2
        val y = -boxHeight - 10

        matrices.translate(0f, y.toFloat(), 0f)

        val alpha = calculateAlpha(dialogue.startTime, dialogue.endTime, dialogue.shouldRemove)
        if (alpha <= 0f) {
            matrices.pop()
            return
        }

        renderRoundedBackground(matrices, boxWidth, boxHeight, dialogue.color, alpha)
        matrices.translate(0f, 0f, -0.1f)

        val alphaInt = (alpha * 255).toInt().coerceIn(0, 255)
        val textAlpha = alphaInt shl 24

        // Render avatar outside the box, to its left
        if (state.parsedAvatar != null) {
            val avatarText = Text.literal(state.parsedAvatar)
            val avatarWidth = textRenderer.getWidth(avatarText)
            val avatarHeight = 42
            val avatarX = x - avatarWidth - 8
            val avatarY = (boxHeight - avatarHeight) / 2

            val nameColorValue = dialogue.color?.let { if (it.startsWith("#")) it else "#$it" } ?: "#FFCC44"
            val colorRgb = nameColorValue.removePrefix("#").trim().toIntOrNull(16) ?: 0xFFCC44

            renderAvatarFrame(matrices, avatarX - 2, avatarY - 4, avatarWidth + 4, avatarHeight + 8, colorRgb, alpha)
            textRenderer.draw(
                avatarText, avatarX.toFloat(), avatarY.toFloat(),
                textAlpha or 0xFFFFFF, false,
                matrices.peek().positionMatrix, consumers,
                TextRenderer.TextLayerType.NORMAL, 0, 15728880
            )
        }

        if (state.parsedName.isNotEmpty()) {
            renderNPCName(matrices, consumers, textRenderer, state.parsedName, null, x, -15, dialogue.color, alpha)
        }

        renderText(matrices, consumers, textRenderer, wrappedLines, x, 0, boxWidth, boxHeight, alpha)

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

    /**
     * Renders a small semi-transparent dark pill behind action text for readability.
     */
    private fun renderActionPill(matrices: MatrixStack, x: Float, y: Float, w: Float, h: Float, alpha: Float) {
        val matrix = matrices.peek().positionMatrix
        val r = 1f  // corner inset
        val z = 0f

        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)
        RenderSystem.setShader { GameRenderer.getPositionColorProgram() }

        val tessellator = Tessellator.getInstance()
        val buf = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR)

        val bgR = 0f
        val bgG = 0f
        val bgB = 0f

        // Main body (inset by corner radius)
        buf.vertex(matrix, x + r, y + h, z).color(bgR, bgG, bgB, alpha)
        buf.vertex(matrix, x + w - r, y + h, z).color(bgR, bgG, bgB, alpha)
        buf.vertex(matrix, x + w - r, y, z).color(bgR, bgG, bgB, alpha)
        buf.vertex(matrix, x + r, y, z).color(bgR, bgG, bgB, alpha)

        // Top/bottom strips
        buf.vertex(matrix, x, y + h - r, z).color(bgR, bgG, bgB, alpha)
        buf.vertex(matrix, x + w, y + h - r, z).color(bgR, bgG, bgB, alpha)
        buf.vertex(matrix, x + w, y + r, z).color(bgR, bgG, bgB, alpha)
        buf.vertex(matrix, x, y + r, z).color(bgR, bgG, bgB, alpha)

        BufferRenderer.drawWithGlobalProgram(buf.end())

        RenderSystem.disableBlend()
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(true)
    }

    private fun renderRoundedBackground(matrices: MatrixStack, boxWidth: Int, boxHeight: Int, color: String?, alpha: Float = 1f) {
        val matrix = matrices.peek().positionMatrix

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
        val bR = ((borderColor shr 16) and 0xFF) / 255f
        val bG = ((borderColor shr 8) and 0xFF) / 255f
        val bB = (borderColor and 0xFF) / 255f

        val x = -boxWidth / 2f
        val y = 0f
        val bgZ = 0.5f   // Background layer (further from camera)
        val brZ = 0.3f   // Border layer (closer to camera, in front of background)
        val w = boxWidth.toFloat()
        val h = boxHeight.toFloat()
        val r = 4f // corner radius
        val b = 2f // border thickness

        // Helper to draw a filled rect at a specific z
        fun fill(x1: Float, y1: Float, x2: Float, y2: Float, cr: Float, cg: Float, cb: Float, z: Float) {
            bufferBuilder.vertex(matrix, x1, y2, z).color(cr, cg, cb, alpha)
            bufferBuilder.vertex(matrix, x2, y2, z).color(cr, cg, cb, alpha)
            bufferBuilder.vertex(matrix, x2, y1, z).color(cr, cg, cb, alpha)
            bufferBuilder.vertex(matrix, x1, y1, z).color(cr, cg, cb, alpha)
        }

        // Main background (inset by corner radius to leave room for rounded corners)
        // Horizontal strip (full width, excluding top/bottom corner rows)
        fill(x, y + r, x + w, y + h - r, bgR, bgG, bgB, bgZ)
        // Top strip (excluding corners)
        fill(x + r, y, x + w - r, y + r, bgR, bgG, bgB, bgZ)
        // Bottom strip (excluding corners)
        fill(x + r, y + h - r, x + w - r, y + h, bgR, bgG, bgB, bgZ)

        // Corner fills (small squares to approximate rounded corners)
        fill(x + 1, y + 1, x + r, y + r, bgR, bgG, bgB, bgZ)
        fill(x + w - r, y + 1, x + w - 1, y + r, bgR, bgG, bgB, bgZ)
        fill(x + 1, y + h - r, x + r, y + h - 1, bgR, bgG, bgB, bgZ)
        fill(x + w - r, y + h - r, x + w - 1, y + h - 1, bgR, bgG, bgB, bgZ)

        // Border — top edge (between corners)
        fill(x + r, y, x + w - r, y + b, bR, bG, bB, brZ)
        // Border — bottom edge
        fill(x + r, y + h - b, x + w - r, y + h, bR, bG, bB, brZ)
        // Border — left edge
        fill(x, y + r, x + b, y + h - r, bR, bG, bB, brZ)
        // Border — right edge
        fill(x + w - b, y + r, x + w, y + h - r, bR, bG, bB, brZ)

        // Rounded corner borders (L-shaped pieces)
        // Top-left
        fill(x + 1, y, x + r, y + b, bR, bG, bB, brZ)
        fill(x, y + 1, x + b, y + r, bR, bG, bB, brZ)
        // Top-right
        fill(x + w - r, y, x + w - 1, y + b, bR, bG, bB, brZ)
        fill(x + w - b, y + 1, x + w, y + r, bR, bG, bB, brZ)
        // Bottom-left
        fill(x + 1, y + h - b, x + r, y + h, bR, bG, bB, brZ)
        fill(x, y + h - r, x + b, y + h - 1, bR, bG, bB, brZ)
        // Bottom-right
        fill(x + w - r, y + h - b, x + w - 1, y + h, bR, bG, bB, brZ)
        fill(x + w - b, y + h - r, x + w, y + h - 1, bR, bG, bB, brZ)

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
        color: String?,
        alpha: Float = 1f
    ) {
        val nameText = Text.literal(name).styled { it.withBold(true) }
        val nameColorValue = color?.let { if (it.startsWith("#")) it else "#$it" } ?: "#FFCC44"
        val colorRgb = nameColorValue.removePrefix("#").trim().toIntOrNull(16) ?: 0xFFCC44
        val alphaInt = (alpha * 255).toInt().coerceIn(0, 255)
        val colorWithAlpha = (alphaInt shl 24) or colorRgb

        // Render avatar if present
        if (avatar != null) {
            val avatarText = Text.literal(avatar)
            val avatarWidth = textRenderer.getWidth(avatarText)
            val avatarHeight = 42

            val avatarX = x + 2
            val avatarY = y - avatarHeight - 5

            val frameX = avatarX - 2
            val frameY = avatarY - 4
            val frameWidth = avatarWidth + 4
            val frameHeight = avatarHeight + 8

            renderAvatarFrame(matrices, frameX, frameY, frameWidth, frameHeight, colorRgb, alpha)

            textRenderer.draw(
                avatarText,
                avatarX.toFloat(),
                avatarY.toFloat(),
                (alphaInt shl 24) or 0xFFFFFF,
                false,
                matrices.peek().positionMatrix,
                consumers,
                TextRenderer.TextLayerType.NORMAL,
                0,
                15728880
            )
        }

        val nameBgAlphaInt = (alpha * 140).toInt()
        val nameBgColor = (nameBgAlphaInt shl 24)

        drawOutlinedText(
            textRenderer, nameText, x.toFloat(), y.toFloat(), colorWithAlpha,
            matrices.peek().positionMatrix, consumers, nameBgColor,
        )
    }

    private fun renderAvatarFrame(
        matrices: MatrixStack,
        frameX: Int,
        frameY: Int,
        frameWidth: Int,
        frameHeight: Int,
        colorRgb: Int,
        alpha: Float = 1f
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

        val z = 0.5f  // Match background depth

        // Helper function to draw a filled rectangle
        fun fillRect(x1: Int, y1: Int, x2: Int, y2: Int) {
            val fx1 = x1.toFloat()
            val fy1 = y1.toFloat()
            val fx2 = x2.toFloat()
            val fy2 = y2.toFloat()

            bufferBuilder.vertex(matrix, fx1, fy2, z).color(r, g, b, alpha)
            bufferBuilder.vertex(matrix, fx2, fy2, z).color(r, g, b, alpha)
            bufferBuilder.vertex(matrix, fx2, fy1, z).color(r, g, b, alpha)
            bufferBuilder.vertex(matrix, fx1, fy1, z).color(r, g, b, alpha)
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
        boxHeight: Int,
        alpha: Float = 1f
    ) {
        val alphaInt = (alpha * 255).toInt().coerceIn(0, 255)
        val textColor = (alphaInt shl 24) or 0x52130A
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
