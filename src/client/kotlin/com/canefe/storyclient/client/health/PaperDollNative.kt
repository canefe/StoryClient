package com.canefe.storyclient.client.health

import net.minecraft.client.gui.DrawContext
import net.minecraft.util.Identifier
import kotlin.math.roundToInt

/**
 * Native (DrawContext) port of [PaperDoll] — the layered male paper-doll, drawn
 * for the Minecraft-UI Health panel instead of imgui. Geometry is identical to
 * [PaperDoll] (same Nice-Health-Tab-derived piece table), only the draw backend
 * differs: each PNG piece is blitted via [DrawContext.drawTexture], horizontal
 * mirror is a matrix flip, and the red injury tint is [DrawContext.setShaderColor].
 *
 * Unlike the imgui version this draws at ABSOLUTE screen coords ([originX],
 * [originY] in px) rather than an imgui cursor, since a Screen has no cursor.
 */
object PaperDollNative {
    private const val BASE = "textures/health/baseliner/male"
    private const val FIGURE_ASPECT = 360f / 910f

    private data class Piece(
        val file: String,
        val fx: Float,
        val fy: Float,
        val fw: Float,
        val aspect: Float,
        val flip: Boolean = false,
        val wireParts: Set<String> = emptySet(),
    )

    // Identical to PaperDoll.pieces (outline first, then limbs, then center).
    private val pieces = listOf(
        Piece("outline", -0.2111f, -0.0516f, 1.4222f, 0.5000f),
        Piece("torso", 0.1472f, 0.0571f, 0.7111f, 0.5000f, wireParts = setOf("torso", "chest", "tail")),
        Piece("upperarm", 0.6208f, 0.1324f, 0.3556f, 0.5000f, wireParts = setOf("right_arm")),
        Piece("lowerarm", 0.7056f, 0.2676f, 0.3556f, 0.5000f, wireParts = setOf("right_arm")),
        Piece("hand", 0.6972f, 0.4423f, 0.3556f, 1.0000f, wireParts = setOf("right_hand", "right_claw")),
        Piece("leg", 0.4583f, 0.4000f, 0.3556f, 0.2500f, wireParts = setOf("right_leg")),
        Piece("feet", 0.5319f, 0.8764f, 0.3556f, 1.0000f, wireParts = setOf("right_foot")),
        Piece("upperarm", 0.0236f, 0.1324f, 0.3556f, 0.5000f, flip = true, wireParts = setOf("left_arm")),
        Piece("lowerarm", -0.0611f, 0.2676f, 0.3556f, 0.5000f, flip = true, wireParts = setOf("left_arm")),
        Piece("hand", -0.0528f, 0.4423f, 0.3556f, 1.0000f, flip = true, wireParts = setOf("left_hand", "left_claw")),
        Piece("leg", 0.1861f, 0.4000f, 0.3556f, 0.2500f, flip = true, wireParts = setOf("left_leg")),
        Piece("feet", 0.1125f, 0.8764f, 0.3556f, 1.0000f, flip = true, wireParts = setOf("left_foot")),
        Piece("neck", 0.3250f, 0.0835f, 0.3556f, 1.0000f, wireParts = setOf("neck")),
        Piece("head", 0.3250f, -0.0456f, 0.3556f, 0.5000f, wireParts = setOf("head", "left_eye", "right_eye", "pointed_ears")),
    )

    private fun tex(file: String) = Identifier.of("storyclient", "$BASE/$file.png")

    fun boxHeight(boxW: Int): Int = (boxW / FIGURE_ASPECT).roundToInt()

    /**
     * Draw the composited figure with its box top-left at ([originX],[originY]),
     * fitting width [boxW]. A piece tints red if it represents an injured wire
     * part. Returns the box height (so the caller can advance past it).
     */
    fun render(ctx: DrawContext, originX: Int, originY: Int, boxW: Int, injuredWireParts: Set<String>): Int {
        val boxH = boxHeight(boxW)
        val boxHf = boxW / FIGURE_ASPECT

        for (p in pieces) {
            val w = (p.fw * boxW).roundToInt()
            val h = (w / p.aspect).roundToInt()
            if (w <= 0 || h <= 0) continue
            val px = originX + (p.fx * boxW).roundToInt()
            val py = originY + (p.fy * boxHf).roundToInt()

            val injured = p.wireParts.any { it in injuredWireParts }
            if (injured) ctx.setShaderColor(1f, 0.35f, 0.35f, 1f)

            blitPiece(ctx, tex(p.file), px, py, w, h, p.flip)

            if (injured) ctx.setShaderColor(1f, 1f, 1f, 1f)
        }

        OrganRowNative.render(ctx, injuredWireParts, originX, originY, boxW, boxHf)
        return boxH
    }

    /**
     * Blit a full-texture piece into a w×h box at (x,y). [flip] mirrors it
     * horizontally by SAMPLING the source right-to-left (u starts at the right
     * edge, regionWidth is negative), NOT by a matrix scale(-1,1). A negative-x
     * matrix scale reverses the quad's winding, which the 1.21.1 GUI pipeline
     * renders dark — the UV mirror keeps winding intact so the tint/shading is
     * correct. (Confirmed by diagnosis: un-flipped injured pieces tinted fine.)
     */
    private fun blitPiece(ctx: DrawContext, id: Identifier, x: Int, y: Int, w: Int, h: Int, flip: Boolean) {
        // (x,y,w,h, u,v, regionW,regionH, texW,texH): region==tex ⇒ samples the
        // whole image scaled into w×h. Negative regionW + u at the right edge
        // mirrors horizontally without touching the matrix stack.
        val u = if (flip) w.toFloat() else 0f
        val regionW = if (flip) -w else w
        ctx.drawTexture(id, x, y, w, h, u, 0f, regionW, h, w, h)
    }
}
