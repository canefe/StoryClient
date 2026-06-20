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

    /**
     * One composited piece. aspect = nativeWidth / nativeHeight. [flip]
     * horizontally mirrors the art (the source limb pieces are drawn for the
     * figure's right side; the left side reuses the same art mirrored).
     */
    private data class Piece(
        val part: String,
        val file: String,
        val fx: Float,
        val fy: Float,
        val fw: Float,
        val aspect: Float,
        val flip: Boolean = false,
    )

    // Back→front. "right"/"left" are the FIGURE's sides (viewer sees right-figure
    // on the left of the screen). Left-side limbs reuse the right art mirrored.
    private val pieces = listOf(
        Piece("outline", "outline", 0.00f, 0.00f, 1.00f, 0.50f),
        // Legs seated onto the outline's legs.
        Piece("leg", "leg", 0.36f, 0.52f, 0.15f, 0.25f),                 // right leg
        Piece("leg", "leg", 0.49f, 0.52f, 0.15f, 0.25f, flip = true),    // left leg
        Piece("torso", "torso", 0.31f, 0.18f, 0.38f, 0.50f),
        // Arms hang down along the torso: upper from shoulder, lower below it,
        // hand at the bottom — tucked closer in than before.
        Piece("upperarm", "upperarm", 0.22f, 0.20f, 0.14f, 0.50f),               // right
        Piece("upperarm", "upperarm", 0.64f, 0.20f, 0.14f, 0.50f, flip = true),  // left
        Piece("lowerarm", "lowerarm", 0.21f, 0.33f, 0.13f, 0.50f),
        Piece("lowerarm", "lowerarm", 0.66f, 0.33f, 0.13f, 0.50f, flip = true),
        Piece("hand", "hand", 0.21f, 0.45f, 0.11f, 1.00f),
        Piece("hand", "hand", 0.68f, 0.45f, 0.11f, 1.00f, flip = true),
        Piece("feet", "feet", 0.36f, 0.93f, 0.12f, 1.00f),
        Piece("feet", "feet", 0.52f, 0.93f, 0.12f, 1.00f, flip = true),
        Piece("neck", "neck", 0.44f, 0.15f, 0.12f, 1.00f),
        Piece("head", "head", 0.37f, 0.01f, 0.26f, 0.50f),
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

            // Horizontal mirror = swap the U coords (u0=1, u1=0).
            val u0 = if (p.flip) 1f else 0f
            val u1 = if (p.flip) 0f else 1f

            if (p.part in injuredParts) {
                ImGui.image(tid, w, h, u0, 0f, u1, 1f, 1f, 0.35f, 0.35f, 1f)
            } else {
                ImGui.image(tid, w, h, u0, 0f, u1, 1f, 1f, 1f, 1f, 1f)
            }
        }

        // Advance cursor past the figure so following content doesn't overlap.
        ImGui.setCursorPos(originX, originY + boxH)
    }
}
