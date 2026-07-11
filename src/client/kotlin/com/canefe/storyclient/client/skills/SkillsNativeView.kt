package com.canefe.storyclient.client.skills

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.util.Identifier
import kotlin.math.roundToInt

/**
 * Native (DrawContext) renderer for the local player's Skills panel — the
 * Minecraft-UI replacement for the imgui [SkillsView]. Drawn as a wood-framed
 * panel to the side of the inventory screen (see the InventoryScreen mixin),
 * so it reads as part of vanilla's UI rather than a floating imgui window.
 *
 * Read-only proof-of-concept: no widgets, no input — just the same grouped
 * skill list [SkillsView] drew, painted with [DrawContext] primitives proven in
 * [com.canefe.storyclient.client.hediff.HediffHud] (fill / drawTexture / drawText).
 *
 * Colours are ARGB (0xAARRGGBB), NOT imgui's ABGR — sampled to match the wood
 * inventory look of the old [com.canefe.storyclient.client.health.HealthTheme].
 */
object SkillsNativeView {
    // --- body layout (px); the frame/title are owned by StoryTabsPanel ---
    private const val ICON = 16      // skill icon size
    private const val ROW_H = 22     // per-skill row height (name line + xp bar)
    private const val HEADER_H = 12  // category header height
    private const val XP_BAR_H = 4   // xp bar height
    private const val CAT_GAP = 4    // gap above a category header

    // --- palette (ARGB) — body colors only (frame colors live in the panel) ---
    private const val PARCHMENT = 0xFFE8D8B8.toInt()   // body text
    private const val HEADER_COL = 0xFFD1B375.toInt()  // category header accent
    private const val SEP_COL = 0xFF3D2914.toInt()     // category separator line
    private const val XP_TRACK = 0xFF1A120F.toInt()    // xp bar track (recess)

    /** Vanilla item texture sheet is 16×16 under assets/minecraft/textures/. */
    private const val TEX_SIZE = 16

    /** grey (untrained) → bronze → gold → bright, as ARGB, by competence 0..1. */
    private fun levelColor(frac: Float): Int {
        val f = frac.coerceIn(0f, 1f)
        return when {
            f <= 0f -> 0xFF8C857A.toInt()   // grey
            f < 0.5f -> 0xFFB38C57.toInt()  // bronze
            f < 0.85f -> 0xFFD6B366.toInt() // gold
            else -> 0xFFFCE08C.toInt()      // bright
        }
    }

    private fun prettyCategory(c: String): String =
        if (c.isBlank()) "Other"
        else c.split('_').joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }

    /** Dim an ARGB colour's RGB channels by [mul] (keeps alpha). */
    private fun dim(argb: Int, mul: Float): Int {
        val a = argb ushr 24 and 0xFF
        val r = ((argb ushr 16 and 0xFF) * mul).roundToInt().coerceIn(0, 255)
        val g = ((argb ushr 8 and 0xFF) * mul).roundToInt().coerceIn(0, 255)
        val b = ((argb and 0xFF) * mul).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun iconTex(skillId: String): Identifier {
        val path = SkillIcons.pathFor(skillId)
        return Identifier.of("minecraft", "textures/$path.png")
    }

    /** Pixel height of the Skills BODY (no frame/title), for the panel to size to. */
    fun contentHeight(skills: List<SkillEntry>): Int {
        if (skills.isEmpty()) return ROW_H
        val categories = skills.groupBy { it.category }.keys.size
        return categories * (CAT_GAP + HEADER_H) + skills.size * ROW_H
    }

    /**
     * Draw the Skills BODY into the well at ([x],[y]) with width [w]; the frame
     * and title bar are drawn by [com.canefe.storyclient.client.panel.StoryTabsPanel].
     */
    fun renderBody(ctx: DrawContext, x: Int, y: Int, w: Int, skills: List<SkillEntry>) {
        val client = MinecraftClient.getInstance()
        val tr = client.textRenderer

        if (skills.isEmpty()) {
            ctx.drawText(tr, "No skills.", x, y, dim(PARCHMENT, 0.6f), false)
            return
        }

        val byCategory = skills.groupBy { it.category }.toSortedMap()
        var cy = y
        var first = true
        for ((category, list) in byCategory) {
            if (!first) {
                ctx.fill(x, cy - 2, x + w, cy - 1, SEP_COL) // separator
            }
            first = false
            cy += CAT_GAP
            ctx.drawText(tr, prettyCategory(category).uppercase(), x, cy, HEADER_COL, false)
            cy += HEADER_H
            for (e in list.sortedByDescending { it.value }) {
                drawRow(ctx, tr, x, cy, w, e)
                cy += ROW_H
            }
        }
    }

    private fun drawRow(
        ctx: DrawContext,
        tr: net.minecraft.client.font.TextRenderer,
        x: Int,
        y: Int,
        w: Int,
        e: SkillEntry,
    ) {
        val frac = (e.value / e.maxValue.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val col = levelColor(frac)
        val xpPct = (e.xpFraction.coerceIn(0f, 1f) * 100).toInt()

        // icon at the body's left edge
        ctx.drawTexture(iconTex(e.id), x, y, ICON, ICON, 0f, 0f, TEX_SIZE, TEX_SIZE, TEX_SIZE, TEX_SIZE)

        val textX = x + ICON + 4
        val barX2 = x + w

        // xp% is right-aligned so it's always flush inside the frame; the
        // label/level line fills the space to its left and is trimmed if a long
        // label (e.g. "Runesmithing") would otherwise spill past it.
        val xpText = "xp $xpPct%"
        val xpW = tr.getWidth(xpText)
        ctx.drawText(tr, xpText, barX2 - xpW, y + 1, col, false)

        val line = "${e.label}  Lvl ${e.level}"
        val trimmed = tr.trimToWidth(line, (barX2 - xpW - 4) - textX)
        ctx.drawText(tr, trimmed, textX, y + 1, col, false)

        // xp bar under the text: recessed track + tinted fill.
        val barX = textX
        val barY = y + 12
        ctx.fill(barX, barY, barX2, barY + XP_BAR_H, XP_TRACK)
        val fillW = ((barX2 - barX) * e.xpFraction.coerceIn(0f, 1f)).roundToInt()
        if (fillW > 0) ctx.fill(barX, barY, barX + fillW, barY + XP_BAR_H, col)
    }
}
