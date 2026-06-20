package com.canefe.storyclient.client.health

import imgui.ImGui
import net.minecraft.client.MinecraftClient
import net.minecraft.util.Identifier

/**
 * Layered male paper-doll. Geometry is ported verbatim from the "Nice Health
 * Tab" RimWorld mod's `Defs/Baseliner/Male/Male.xml` (HumanlikeDoll +
 * DollBodyPart), so the pieces composite exactly as the original art was
 * authored — instead of being hand-eyeballed onto the outline.
 *
 * The mod uses a center-origin, +y-DOWN pixel space with a bounding box of
 * x∈[-180,180], y∈[-465,445] (360×910). Each part's `position` is the piece
 * CENTER and `width/height = -1` means mirror (the figure's left side reuses
 * the right art flipped). We convert all of that to top-left fractions of the
 * box here: fx/fy = top-left as a fraction of box w/h, fw = width as a fraction
 * of box width, aspect = nativeW/nativeH (height derived so nothing distorts),
 * flip = horizontal mirror.
 */
object PaperDoll {
    private const val BASE = "textures/health/baseliner/male"

    // Box is 360 wide × 910 tall (from Male.xml BoundingBox).
    private const val FIGURE_ASPECT = 360f / 910f // ≈ 0.3956

    /**
     * One composited piece. aspect = nativeWidth / nativeHeight; flip = mirror.
     * [wireParts] is the set of sim body-part ids this piece represents, so a
     * left_arm injury tints ONLY the figure's left arm pieces (eyes/ears fold
     * onto the head, claws onto the matching hand, tail onto the torso). Empty =
     * never tints (outline, torso backdrop overlap).
     */
    private data class Piece(
        val part: String,
        val file: String,
        val fx: Float,
        val fy: Float,
        val fw: Float,
        val aspect: Float,
        val flip: Boolean = false,
        val wireParts: Set<String> = emptySet(),
    )

    // Ordered by the mod's layer field (0=back). Values computed from Male.xml
    // centers/sizes; "right"/"left" are the FIGURE's sides. The outline is the
    // mod's separate outlinePath, drawn first as the backdrop — it overhangs the
    // box (fx<0, fw>1) exactly as the source texture does.
    private val pieces = listOf(
        Piece("outline", "outline", -0.2111f, -0.0516f, 1.4222f, 0.5000f),
        Piece("torso", "torso", 0.1472f, 0.0571f, 0.7111f, 0.5000f, wireParts = setOf("torso", "chest", "tail")),
        // Screen-RIGHT pieces. The doll faces the viewer (front view), so by
        // mirror convention the character's LEFT limbs appear on the viewer's
        // right — hence these carry the left_* wire parts.
        Piece("upperarm", "upperarm", 0.6208f, 0.1324f, 0.3556f, 0.5000f, wireParts = setOf("left_arm")),
        Piece("lowerarm", "lowerarm", 0.7056f, 0.2676f, 0.3556f, 0.5000f, wireParts = setOf("left_arm")),
        Piece("hand", "hand", 0.6972f, 0.4423f, 0.3556f, 1.0000f, wireParts = setOf("left_hand", "left_claw")),
        Piece("leg", "leg", 0.4583f, 0.4000f, 0.3556f, 0.2500f, wireParts = setOf("left_leg")),
        Piece("feet", "feet", 0.5319f, 0.8764f, 0.3556f, 1.0000f, wireParts = setOf("left_foot")),
        // Screen-LEFT pieces (same art mirrored) → the character's RIGHT limbs.
        Piece("upperarm", "upperarm", 0.0236f, 0.1324f, 0.3556f, 0.5000f, flip = true, wireParts = setOf("right_arm")),
        Piece("lowerarm", "lowerarm", -0.0611f, 0.2676f, 0.3556f, 0.5000f, flip = true, wireParts = setOf("right_arm")),
        Piece("hand", "hand", -0.0528f, 0.4423f, 0.3556f, 1.0000f, flip = true, wireParts = setOf("right_hand", "right_claw")),
        Piece("leg", "leg", 0.1861f, 0.4000f, 0.3556f, 0.2500f, flip = true, wireParts = setOf("right_leg")),
        Piece("feet", "feet", 0.1125f, 0.8764f, 0.3556f, 1.0000f, flip = true, wireParts = setOf("right_foot")),
        // Center.
        Piece("neck", "neck", 0.3250f, 0.0835f, 0.3556f, 1.0000f, wireParts = setOf("neck")),
        Piece("head", "head", 0.3250f, -0.0456f, 0.3556f, 0.5000f, wireParts = setOf("head", "left_eye", "right_eye", "pointed_ears")),
    )

    private fun glId(file: String): Long {
        val id = Identifier.of("storyclient", "$BASE/$file.png")
        val tm = MinecraftClient.getInstance().textureManager
        tm.bindTexture(id) // force upload to GL on first use
        return tm.getTexture(id).glId.toLong()
    }

    /**
     * Draw the composited figure at the current cursor, fitting within [boxW].
     * [injuredWireParts] holds the sim body-part ids currently injured (e.g.
     * "left_arm"); a piece tints red only if it represents one of them, so the
     * correct side lights up.
     */
    fun render(injuredWireParts: Set<String>, boxW: Float = 120f) {
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

            val injured = p.wireParts.any { it in injuredWireParts }
            if (injured) {
                ImGui.image(tid, w, h, u0, 0f, u1, 1f, 1f, 0.35f, 0.35f, 1f)
            } else {
                ImGui.image(tid, w, h, u0, 0f, u1, 1f, 1f, 1f, 1f, 1f)
            }
        }

        // Advance cursor past the figure so following content doesn't overlap.
        ImGui.setCursorPos(originX, originY + boxH)
    }
}
