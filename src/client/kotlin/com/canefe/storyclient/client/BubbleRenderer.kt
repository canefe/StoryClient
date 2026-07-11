package com.canefe.storyclient.client

import com.canefe.storyclient.client.wheel.NearbyNPCCache
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

        // Stable per-NPC randomization so positions don't reshuffle frame-to-frame.
        val seed = npcId.hashCode().toLong()
        val rng = Random(seed)

        // Anchor at mid-body height (mirror of Helix card / sticky-action pill anchor).
        // Helix + sticky pill live on the LEFT flank; we put dialogue actions on the
        // RIGHT flank as a single column so they read as one tidy stack instead of
        // scattering across the sky above the head where multiple NPCs' columns collide.
        val anchorPos = entityPos.add(0.0, (entityHeight * 0.5).toDouble(), 0.0)

        val difference = cameraPos.subtract(anchorPos)
        val yaw = -(atan2(difference.z, difference.x) + PI / 2.0)
        val horizontalDistance = sqrt(difference.x * difference.x + difference.z * difference.z)
        val pitch = atan2(difference.y, horizontalDistance)

        // Stable per-row x jitter so the column has a hand-written feel without
        // reshuffling every frame.
        val xJittersPx = wordGroups.indices.map { (rng.nextDouble() * 6.0 - 3.0).toFloat() }
        val tilts = wordGroups.indices.map { (rng.nextDouble() * 8.0 - 4.0).toFloat() }

        val now = System.currentTimeMillis()
        val staggerDelayMs = 300L
        val popDurationMs = 400L

        // Right-flank column geometry (post-scale pixels). Hug the body — small inner
        // offset, single column. After the -X flip below, screen-right is post-scale
        // negative X (same convention as PerceptionPopupRenderer / Helix card).
        val innerEdgePx = 20f         // distance from centerline to column's left edge
        val rowHeightPx = textRenderer.fontHeight + 4f
        val totalH = wordGroups.size * rowHeightPx
        val topYPx = -totalH / 2f     // center column vertically on the anchor

        for ((i, group) in wordGroups.withIndex()) {
            val groupStartTime = action.startTime + (i * staggerDelayMs)
            val elapsed = now - groupStartTime
            if (elapsed < 0) continue

            val rawProgress = (elapsed.toFloat() / popDurationMs).coerceIn(0f, 1f)
            val popScale = if (rawProgress < 1f) {
                val t = rawProgress
                val overshoot = 1.15f
                1f - (1f - t) * (1f - t) * (1f - overshoot * t)
            } else 1f
            if (popScale <= 0.01f) continue

            matrices.push()
            matrices.translate(
                anchorPos.x - cameraPos.x,
                anchorPos.y - cameraPos.y,
                anchorPos.z - cameraPos.z,
            )
            matrices.multiply(Quaternionf().rotationY(yaw.toFloat()))
            matrices.multiply(Quaternionf().rotationX(pitch.toFloat()))
            matrices.multiply(Quaternionf().rotationZ(Math.toRadians(tilts[i].toDouble()).toFloat()))

            val animatedScale = actionScale * popScale
            matrices.scale(-animatedScale, -animatedScale, animatedScale)

            val displayText = Text.literal(group).styled { it.withItalic(true).withColor(actionColorRgb) }
            val textWidth = textRenderer.getWidth(displayText)

            // Right flank in screen space → positive post-scale X (Helix card and the
            // sticky-action pill use NEGATIVE X and land on the left, so we mirror that).
            // Slide-in: rows enter from further out (more positive) and ease toward innerEdgePx.
            val slideOffset = (1f - rawProgress) * 18f
            val leftEdgeXPostScale = innerEdgePx + slideOffset + xJittersPx[i]
            val textX = leftEdgeXPostScale
            val textY = topYPx + i * rowHeightPx

            val alphaInt = (rawProgress.coerceIn(0f, 1f) * 255).toInt()
            val textColor = (alphaInt shl 24) or actionColorRgb

            // Counter-scale so per-row pop-overshoot scales around the row's own anchor,
            // not the column's. Keeps the column visually stable as rows pop in.
            val drawX = textX / popScale
            val drawY = textY / popScale

            drawOutlinedText(
                textRenderer, displayText, drawX, drawY, textColor,
                matrices.peek().positionMatrix, consumers, 0,
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
        // Black copy of the text (preserving alpha/style) used for both the
        // outline and the drop-shadow so they sit correctly behind the main text.
        val darkText by lazy {
            Text.literal(text.string).styled {
                it.withColor(0x000000).withBold(text.style.isBold).withItalic(text.style.isItalic)
            }
        }
        val darkColor = (alpha shl 24) // pure black with the text's alpha

        // The dark copies (shadow + outline) and the main text are coplanar in this
        // billboard, so at the same Z they z-fight and the dark can win in front of a
        // glyph (the "shadow in front" bug, worse up close where the tiny Z gap falls
        // below depth-buffer precision; the tilted, spaced-out action words rarely
        // overlap so they looked fine). Push the dark copies AWAY from the camera in
        // local Z so they are unambiguously behind. This matrix already has the ~0.02
        // billboard scale baked in, so local Z is compressed hard — the push has to be
        // large in local units to clear depth precision at close range. Z scale is
        // positive and the billboard faces the camera, so +local Z = farther = behind.
        val shadowMatrix = Matrix4f(matrix).translate(0f, 0f, 3f)

        // Drop-shadow: a single dark copy offset down-right.
        if (StoryClientConfig.bubbleTextShadow) {
            textRenderer.draw(
                darkText, x + 1f, y + 1f, darkColor, false,
                shadowMatrix, consumers, TextRenderer.TextLayerType.SEE_THROUGH, 0, light,
            )
        }

        // 8-direction outline at 1px offset for a clean, pill-free border.
        if (StoryClientConfig.bubbleTextOutline) {
            val offsets = arrayOf(
                -1f to -1f, 0f to -1f, 1f to -1f,
                -1f to 0f,            1f to 0f,
                -1f to 1f,  0f to 1f, 1f to 1f,
            )
            for ((dx, dy) in offsets) {
                textRenderer.draw(
                    darkText, x + dx, y + dy, darkColor, false,
                    shadowMatrix, consumers, TextRenderer.TextLayerType.SEE_THROUGH, 0, light,
                )
            }
        }
        // Main text on top, no background pill.
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
        val entityPos = getInterpolatedPosition(entity, tickDelta)
        val entityHeight = entity.height
        val anchorPos = entityPos.add(0.0, entityHeight.toDouble() + 0.4 + StoryClientConfig.bubbleYOffset, 0.0)

        val difference = cameraPos.subtract(anchorPos)
        val yaw = -(atan2(difference.z, difference.x) + PI / 2.0)
        val horizontalDistance = sqrt(difference.x * difference.x + difference.z * difference.z)
        val pitch = atan2(difference.y, horizontalDistance)

        val textRenderer = MinecraftClient.getInstance().textRenderer
        val scale = SCALE_FACTOR * StoryClientConfig.dialogueScale.toFloat()

        val words = dialogue.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return

        val displayName = recognitionLabelFor(state.npcId) ?: state.parsedName
        val nameColorValue = dialogue.color?.let { if (it.startsWith("#")) it else "#$it" } ?: "#FFCC44"
        val nameColorRgb = nameColorValue.removePrefix("#").trim().toIntOrNull(16) ?: 0xFFCC44

        val now = System.currentTimeMillis()
        val staggerDelayMs = 120L  // gap between successive words appearing
        val popDurationMs = 220L   // per-word scale-up duration
        val lineHeight = textRenderer.fontHeight + 2
        val maxLineWidth = 180  // pixels (text-renderer space) before wrapping

        // Pre-compute stable line layout from the full text so words don't reflow as they pop in.
        data class LaidWord(val text: String, val lineIdx: Int, val xInLine: Float, val width: Int)
        val laid = mutableListOf<LaidWord>()
        var lineIdx = 0
        var cursorX = 0f
        val spaceWidth = textRenderer.getWidth(" ").toFloat()
        for (w in words) {
            val wWidth = textRenderer.getWidth(w)
            val needsSpace = cursorX > 0f
            val prospective = cursorX + (if (needsSpace) spaceWidth else 0f) + wWidth
            if (cursorX > 0f && prospective > maxLineWidth) {
                lineIdx += 1
                cursorX = 0f
            }
            val x = cursorX + (if (cursorX > 0f) spaceWidth else 0f)
            laid += LaidWord(w, lineIdx, x, wWidth)
            cursorX = x + wWidth
        }
        val lineCount = lineIdx + 1
        val lineWidths = IntArray(lineCount)
        for (lw in laid) {
            val end = (lw.xInLine + lw.width).toInt()
            if (end > lineWidths[lw.lineIdx]) lineWidths[lw.lineIdx] = end
        }

        val nameLines = if (displayName.isNotEmpty()) 1 else 0
        val totalLines = lineCount + nameLines
        val topY = -(totalLines * lineHeight) - 4  // -4 so the whole block sits a touch higher above the head
        val firstTextLineY = topY + (nameLines * lineHeight)

        val dialogueAlpha = calculateAlpha(dialogue.startTime, dialogue.endTime, dialogue.shouldRemove)
        if (dialogueAlpha <= 0f) return

        fun pushBillboard() {
            matrices.push()
            matrices.translate(
                anchorPos.x - cameraPos.x,
                anchorPos.y - cameraPos.y,
                anchorPos.z - cameraPos.z,
            )
            matrices.multiply(Quaternionf().rotationY(yaw.toFloat()))
            matrices.multiply(Quaternionf().rotationX(pitch.toFloat()))
        }

        // Speaker name (top, not staggered)
        if (displayName.isNotEmpty()) {
            pushBillboard()
            matrices.scale(-scale, -scale, scale)
            val nameText = Text.literal(displayName).styled { it.withBold(true) }
            val nameWidth = textRenderer.getWidth(nameText)
            val alphaInt = (dialogueAlpha * 255).toInt().coerceIn(0, 255)
            val nameColor = (alphaInt shl 24) or nameColorRgb
            drawOutlinedText(
                textRenderer, nameText,
                -nameWidth / 2f, topY.toFloat(),
                nameColor, matrices.peek().positionMatrix, consumers, 0,
            )
            matrices.pop()
        }

        // Per-word pop-in. Each word stays in place once shown; only the currently popping word scales.
        for ((i, lw) in laid.withIndex()) {
            val wordStart = dialogue.startTime + (i * staggerDelayMs)
            val elapsed = now - wordStart
            if (elapsed < 0) continue

            val rawProgress = (elapsed.toFloat() / popDurationMs).coerceIn(0f, 1f)
            val popScale = if (rawProgress < 1f) {
                val t = rawProgress
                val overshoot = 1.15f
                1f - (1f - t) * (1f - t) * (1f - overshoot * t)
            } else {
                1f
            }
            if (popScale <= 0.01f) continue

            val wordAlpha = dialogueAlpha * rawProgress
            if (wordAlpha <= 0f) continue

            // Line layout: center each line horizontally around x=0.
            val lineW = lineWidths[lw.lineIdx]
            val lineLeft = -lineW / 2f
            val wordCenterX = lineLeft + lw.xInLine + lw.width / 2f
            val wordY = firstTextLineY + lw.lineIdx * lineHeight

            pushBillboard()
            // Apply per-word pop scale around the word's own center so it grows in place.
            val animatedScale = scale * popScale
            matrices.scale(-animatedScale, -animatedScale, animatedScale)

            val alphaInt = (wordAlpha * 255).toInt().coerceIn(0, 255)
            val textColor = (alphaInt shl 24) or 0xFFFFFF

            val displayText = Text.literal(lw.text)
            // Because we scaled by popScale, the un-scaled positions get multiplied. Divide both
            // axes by popScale so the word's screen-space center and baseline stay put while it pops.
            val drawX = (wordCenterX - lw.width / 2f) / popScale
            val drawY = wordY.toFloat() / popScale

            drawOutlinedText(
                textRenderer, displayText,
                drawX, drawY,
                textColor, matrices.peek().positionMatrix, consumers, 0,
            )

            matrices.pop()
        }
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

    /**
     * Resolves the per-perceiver display label from NearbyNPCCache so the
     * bubble respects recognition: descriptor when the local player doesn't
     * know the speaker, real name when they do. Returns null if the speaker
     * isn't in the nearby cache (e.g. unmapped player chat) — caller should
     * fall back to the parsed name from the wire payload.
     */
    private fun recognitionLabelFor(npcId: String): String? {
        val uuid = try { java.util.UUID.fromString(npcId) } catch (_: Exception) { return null }
        return NearbyNPCCache.get(uuid)?.name?.takeIf { it.isNotBlank() }
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
}
