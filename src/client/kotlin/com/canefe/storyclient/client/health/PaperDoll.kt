package com.canefe.storyclient.client.health

import imgui.ImGui
import net.minecraft.client.MinecraftClient
import net.minecraft.util.Identifier

/**
 * Layered male paper-doll. The source RimWorld pieces are different pixel sizes
 * and are meant to be composited at anatomical positions, NOT stretched to a
 * common box — doing the latter smashes every limb onto the torso.
 *
 * Each piece is placed as fractions of the figure box: [fx],[fy] is the
 * top-left as a fraction of figure width/height, [fw] is the width as a
 * fraction of figure width, and the height is derived from the piece's native
 * aspect ratio so nothing is distorted. The figure box itself is sized off the
 * `outline` reference (512x1024 → aspect 0.5).
 */
object PaperDoll {
    private const val BASE = "textures/health/baseliner/male"

    private const val FIGURE_ASPECT = 0.5f // outline 512x1024

    /** One composited piece. aspect = nativeWidth / nativeHeight. */
    private data class Piece(
        val part: String,
        val file: String,
        val fx: Float,
        val fy: Float,
        val fw: Float,
        val aspect: Float,
    )

    // Back→front. Offsets hand-tuned to the male pieces' native aspects:
    // outline 0.5, torso 0.5, head 0.5, neck 1.0, upperarm/lowerarm 0.5,
    // hand 1.0, leg 0.25, feet 1.0.
    private val pieces = listOf(
        Piece("outline", "outline", 0.00f, 0.00f, 1.00f, 0.50f),
        Piece("leg", "leg", 0.34f, 0.55f, 0.16f, 0.25f),   // right leg
        Piece("leg", "leg", 0.50f, 0.55f, 0.16f, 0.25f),   // left leg
        Piece("torso", "torso", 0.30f, 0.20f, 0.40f, 0.50f),
        Piece("upperarm", "upperarm", 0.18f, 0.22f, 0.16f, 0.50f), // right arm
        Piece("upperarm", "upperarm", 0.66f, 0.22f, 0.16f, 0.50f), // left arm
        Piece("lowerarm", "lowerarm", 0.16f, 0.36f, 0.15f, 0.50f),
        Piece("lowerarm", "lowerarm", 0.69f, 0.36f, 0.15f, 0.50f),
        Piece("hand", "hand", 0.15f, 0.50f, 0.12f, 1.00f),
        Piece("hand", "hand", 0.73f, 0.50f, 0.12f, 1.00f),
        Piece("feet", "feet", 0.33f, 0.92f, 0.14f, 1.00f),
        Piece("feet", "feet", 0.53f, 0.92f, 0.14f, 1.00f),
        Piece("neck", "neck", 0.43f, 0.16f, 0.14f, 1.00f),
        Piece("head", "head", 0.36f, 0.02f, 0.28f, 0.50f),
    )

    private fun glId(file: String): Long {
        val id = Identifier.of("storyclient", "$BASE/$file.png")
        val tm = MinecraftClient.getInstance().textureManager
        tm.bindTexture(id) // force upload to GL on first use
        return tm.getTexture(id).glId.toLong()
    }

    /**
     * Draw the composited figure at the current cursor, fitting within [boxW].
     * [injuredParts] holds part ids to tint red.
     */
    fun render(injuredParts: Set<String>, boxW: Float = 120f) {
        val boxH = boxW / FIGURE_ASPECT
        val originX = ImGui.getCursorPosX()
        val originY = ImGui.getCursorPosY()

        for (p in pieces) {
            val tid = glId(p.file)
            if (tid <= 0L) continue // not loaded yet; valid next frame

            val w = p.fw * boxW
            val h = w / p.aspect
            ImGui.setCursorPos(originX + p.fx * boxW, originY + p.fy * boxH)

            if (p.part in injuredParts) {
                ImGui.image(tid, w, h, 0f, 0f, 1f, 1f, 1f, 0.35f, 0.35f, 1f)
            } else {
                ImGui.image(tid, w, h, 0f, 0f, 1f, 1f, 1f, 1f, 1f, 1f)
            }
        }

        // Advance cursor past the figure so following content doesn't overlap.
        ImGui.setCursorPos(originX, originY + boxH)
    }
}
