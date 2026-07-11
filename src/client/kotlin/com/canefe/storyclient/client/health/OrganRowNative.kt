package com.canefe.storyclient.client.health

import net.minecraft.client.gui.DrawContext
import net.minecraft.util.Identifier
import kotlin.math.roundToInt

/**
 * Native (DrawContext) port of [OrganRow] — injured internal organs overlaid on
 * the figure at their anatomical positions. Geometry identical to [OrganRow];
 * only the draw backend differs (drawTexture + matrix-flip + setShaderColor).
 * Organs draw ONLY when injured, matching the mod's injured-only behavior.
 */
object OrganRowNative {
    private const val BASE = "textures/health/organs"

    private data class OrganPiece(
        val file: String,
        val fx: Float,
        val fy: Float,
        val fw: Float,
        val aspect: Float,
        val flip: Boolean = false,
        val wireParts: Set<String>,
    )

    private val organs = listOf(
        OrganPiece("lung", 0.4131f, 0.1175f, 0.3378f, 0.5000f, wireParts = setOf("right_lung")),
        OrganPiece("lung", 0.2492f, 0.1175f, 0.3378f, 0.5000f, flip = true, wireParts = setOf("left_lung")),
        OrganPiece("heart", 0.3581f, 0.2493f, 0.3200f, 1.0000f, wireParts = setOf("heart")),
        OrganPiece("stomach", 0.3858f, 0.3180f, 0.3200f, 1.0000f, wireParts = setOf("stomach", "intestines")),
        OrganPiece("liver", 0.2525f, 0.3376f, 0.3200f, 2.0000f, wireParts = setOf("liver")),
        OrganPiece("kidney", 0.5325f, 0.3708f, 0.1600f, 0.5000f, wireParts = setOf("right_kidney")),
        OrganPiece("kidney", 0.3075f, 0.3708f, 0.1600f, 0.5000f, flip = true, wireParts = setOf("left_kidney")),
        OrganPiece("brain", 0.3222f, 0.0286f, 0.3556f, 2.0000f, wireParts = setOf("brain")),
    )

    private fun tex(file: String) = Identifier.of("storyclient", "$BASE/$file.png")

    /** Overlay injured organs onto PaperDollNative's box (same coords). */
    fun render(
        ctx: DrawContext,
        injuredWireParts: Set<String>,
        originX: Int,
        originY: Int,
        boxW: Int,
        boxHf: Float,
    ) {
        for (o in organs) {
            if (o.wireParts.none { it in injuredWireParts }) continue // healthy → hidden
            val w = (o.fw * boxW).roundToInt()
            val h = (w / o.aspect).roundToInt()
            if (w <= 0 || h <= 0) continue
            val px = originX + (o.fx * boxW).roundToInt()
            val py = originY + (o.fy * boxHf).roundToInt()

            ctx.setShaderColor(1f, 0.3f, 0.3f, 1f) // injured tint
            // Mirror via negative-regionWidth UV sampling (not a matrix scale,
            // which reverses winding and renders dark — see PaperDollNative).
            val u = if (o.flip) w.toFloat() else 0f
            val regionW = if (o.flip) -w else w
            ctx.drawTexture(tex(o.file), px, py, w, h, u, 0f, regionW, h, w, h)
            ctx.setShaderColor(1f, 1f, 1f, 1f)
        }
    }
}
