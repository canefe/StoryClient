package com.canefe.storyclient.client.skills

import imgui.ImGui
import imgui.flag.ImGuiCol

/**
 * Renders the local player's skills grouped by category, RimWorld-style:
 *  - colored category headers with an accent underline,
 *  - a per-skill icon square (colored placeholder; swaps to real art when
 *    [SkillIcons] PNGs exist),
 *  - name/value/level tinted by competence (grey untrained → gold mastered),
 *  - a 20-segment level pip track, and
 *  - an XP-to-next progress bar tinted to match.
 */
object SkillsView {
    // ImGui draw-list colors are packed ABGR (IM_COL32: a<<24|b<<16|g<<8|r).
    private const val HEADER_ACCENT = 0xFF34_3B4FL.toInt() // #4F3B34 wood bevel
    private const val PIP_OFF = 0xFF20_2A33L.toInt()       // dim recessed pip
    private const val PIP_ON_LO = 0xFF3A_4A5AL.toInt()     // low-level pip (bronze)
    private const val PIP_ON_HI = 0xFF6A_C8F5L.toInt()     // high-level pip (bright gold, ABGR)

    private const val ICON = 22f
    private const val PIP_W = 5f
    private const val PIP_H = 8f
    private const val PIP_GAP = 1f
    private const val MAX_LEVEL = 20

    private fun prettyCategory(c: String): String =
        if (c.isBlank()) "Other"
        else c.split('_').joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }

    /** grey (untrained) → bronze → gold → bright by competence fraction 0..1. */
    private fun levelColor(frac: Float): FloatArray {
        val f = frac.coerceIn(0f, 1f)
        return when {
            f <= 0f -> floatArrayOf(0.45f, 0.42f, 0.38f, 1f)       // grey
            f < 0.5f -> floatArrayOf(0.62f, 0.48f, 0.30f, 1f)      // bronze
            f < 0.85f -> floatArrayOf(0.80f, 0.66f, 0.36f, 1f)     // gold
            else -> floatArrayOf(0.98f, 0.86f, 0.50f, 1f)          // bright
        }
    }

    private fun packAbgr(c: FloatArray): Int {
        val r = (c[0] * 255).toInt() and 0xFF
        val g = (c[1] * 255).toInt() and 0xFF
        val b = (c[2] * 255).toInt() and 0xFF
        return (0xFF shl 24) or (b shl 16) or (g shl 8) or r
    }

    private fun categoryHeader(label: String) {
        val dl = ImGui.getWindowDrawList()
        ImGui.pushStyleColor(ImGuiCol.Text, 0.82f, 0.70f, 0.46f, 1f)
        ImGui.text(label.uppercase())
        ImGui.popStyleColor()
        // Accent underline spanning the content width.
        val minX = ImGui.getWindowPosX() + ImGui.getCursorPosX()
        val y = ImGui.getWindowPosY() + ImGui.getCursorPosY() - 2f
        val maxX = ImGui.getWindowPosX() + ImGui.getWindowWidth() - 8f
        dl.addRectFilled(minX, y, maxX, y + 1.5f, HEADER_ACCENT)
        ImGui.dummy(0f, 3f)
    }

    private fun levelPips(level: Int, frac: Float) {
        val dl = ImGui.getWindowDrawList()
        val startX = ImGui.getWindowPosX() + ImGui.getCursorPosX()
        val y = ImGui.getWindowPosY() + ImGui.getCursorPosY()
        val onColor = packAbgr(levelColor(frac))
        for (i in 0 until MAX_LEVEL) {
            val x = startX + i * (PIP_W + PIP_GAP)
            val col = if (i < level) onColor else PIP_OFF
            dl.addRectFilled(x, y, x + PIP_W, y + PIP_H, col)
        }
        ImGui.dummy(MAX_LEVEL * (PIP_W + PIP_GAP), PIP_H + 2f)
    }

    private fun iconSquare(e: SkillEntry) {
        val dl = ImGui.getWindowDrawList()
        val x = ImGui.getWindowPosX() + ImGui.getCursorPosX()
        val y = ImGui.getWindowPosY() + ImGui.getCursorPosY()
        val frac = (e.value / e.maxValue.coerceAtLeast(1f)).coerceIn(0f, 1f)
        // Colored placeholder tile tinted by level; real art can replace this later.
        dl.addRectFilled(x, y, x + ICON, y + ICON, packAbgr(levelColor(frac)))
        dl.addRect(x, y, x + ICON, y + ICON, HEADER_ACCENT)
        ImGui.dummy(ICON, ICON)
    }

    private fun skillRow(e: SkillEntry) {
        val frac = (e.value / e.maxValue.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val col = levelColor(frac)
        val xpPct = (e.xpFraction.coerceIn(0f, 1f) * 100).toInt()

        iconSquare(e)
        ImGui.sameLine()

        // Right of the icon: name + stats, then pips, then the XP bar.
        val cursorAfterIcon = ImGui.getCursorPosX()
        ImGui.pushStyleColor(ImGuiCol.Text, col[0], col[1], col[2], col[3])
        ImGui.text("${e.label}   ${e.value.toInt()}/${e.maxValue.toInt()}   Lvl ${e.level}   xp $xpPct%")
        ImGui.popStyleColor()

        ImGui.setCursorPosX(cursorAfterIcon)
        levelPips(e.level, frac)

        ImGui.setCursorPosX(cursorAfterIcon)
        ImGui.pushStyleColor(ImGuiCol.PlotHistogram, col[0], col[1], col[2], 1f)
        ImGui.progressBar(e.xpFraction.coerceIn(0f, 1f), -1f, 8f, "")
        ImGui.popStyleColor()

        ImGui.dummy(0f, 4f)
    }

    fun render(skills: List<SkillEntry>) {
        if (skills.isEmpty()) {
            ImGui.textDisabled("No skills.")
            return
        }
        val byCategory = skills.groupBy { it.category }.toSortedMap()
        var first = true
        for ((category, list) in byCategory) {
            if (!first) ImGui.dummy(0f, 4f)
            first = false
            categoryHeader(prettyCategory(category))
            for (e in list.sortedByDescending { it.value }) skillRow(e)
        }
    }
}
