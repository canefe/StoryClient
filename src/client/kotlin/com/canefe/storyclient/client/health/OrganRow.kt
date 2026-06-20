package com.canefe.storyclient.client.health

import imgui.ImGui
import net.minecraft.client.MinecraftClient
import net.minecraft.util.Identifier

/**
 * Internal organs overlaid ON the figure at their anatomical positions (lungs in
 * the chest, heart, liver, kidneys, stomach, brain), ported from the "Nice
 * Health Tab" mod's `MaleOrgans.xml`. Like the mod, an organ is drawn ONLY when
 * it has a hediff (`visibleByDefault=false`) — healthy internals stay hidden so
 * the silhouette reads cleanly. Drawn into the SAME box coordinates as
 * [PaperDoll] so positions line up; [PaperDoll] calls this after the body.
 */
object OrganRow {
    private const val BASE = "textures/health/organs"

    /** fx/fy/fw fractions of the figure box; aspect = nativeW/nativeH; flip = mirror. */
    private data class OrganPiece(
        val file: String,
        val fx: Float,
        val fy: Float,
        val fw: Float,
        val aspect: Float,
        val flip: Boolean = false,
        val wireParts: Set<String>,
    )

    // Positions converted from MaleOrgans.xml (center-origin, +y down, box 360×910).
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

    private fun glId(file: String): Long {
        val id = Identifier.of("storyclient", "$BASE/$file.png")
        val tm = MinecraftClient.getInstance().textureManager
        tm.bindTexture(id)
        return tm.getTexture(id).glId.toLong()
    }

    /**
     * Overlay injured organs onto the figure. [originX]/[originY]/[boxW]/[boxH]
     * are PaperDoll's box; only organs whose wire part is in [injuredWireParts]
     * are drawn (tinted red), matching the mod's injured-only behavior.
     */
    fun render(
        injuredWireParts: Set<String>,
        originX: Float,
        originY: Float,
        boxW: Float,
        boxH: Float,
    ) {
        for (o in organs) {
            if (o.wireParts.none { it in injuredWireParts }) continue // healthy → hidden
            val tid = glId(o.file)
            if (tid <= 0L) continue

            val w = o.fw * boxW
            val h = w / o.aspect
            ImGui.setCursorPos(originX + o.fx * boxW, originY + o.fy * boxH)
            val u0 = if (o.flip) 1f else 0f
            val u1 = if (o.flip) 0f else 1f
            // Injured organ: red tint (drawn only when injured anyway).
            ImGui.image(tid, w, h, u0, 0f, u1, 1f, 1f, 0.3f, 0.3f, 1f)
        }
    }
}
