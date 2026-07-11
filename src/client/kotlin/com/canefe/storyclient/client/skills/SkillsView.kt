package com.canefe.storyclient.client.skills

import imgui.ImGui
import imgui.flag.ImGuiCol

/**
 * Renders the local player's skills grouped by category, RimWorld-style:
 *  - colored category headers,
 *  - name/value/level tinted by competence (grey untrained → bright gold),
 *  - a text pip track for the level band, and
 *  - an XP-to-next progress bar tinted to match.
 *
 * Built only from primitives proven elsewhere in the health UI (text,
 * pushStyleColor, progressBar, separator) so it can't throw at draw time.
 */
object SkillsView {
    private const val ICON = 20f

    private fun prettyCategory(c: String): String =
        if (c.isBlank()) "Other"
        else c.split('_').joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }

    /** grey (untrained) → bronze → gold → bright by competence fraction 0..1. */
    private fun levelColor(frac: Float): FloatArray {
        val f = frac.coerceIn(0f, 1f)
        return when {
            f <= 0f -> floatArrayOf(0.55f, 0.52f, 0.47f, 1f)   // grey
            f < 0.5f -> floatArrayOf(0.70f, 0.55f, 0.34f, 1f)  // bronze
            f < 0.85f -> floatArrayOf(0.84f, 0.70f, 0.40f, 1f) // gold
            else -> floatArrayOf(0.99f, 0.88f, 0.55f, 1f)      // bright
        }
    }

    private fun skillRow(e: SkillEntry) {
        val frac = (e.value / e.maxValue.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val col = levelColor(frac)
        val xpPct = (e.xpFraction.coerceIn(0f, 1f) * 100).toInt()

        // Icon left of the name, via the SAME proven ImGui.image path the Health
        // tab's paper-doll uses. Rendered at full color (it's a real item sprite,
        // not a placeholder). If the texture isn't loaded (tid <= 0) we omit the
        // image and let the text start at the margin.
        val tid = SkillIcons.glId(e.id)
        if (tid > 0L) {
            ImGui.image(tid, ICON, ICON, 0f, 0f, 1f, 1f, 1f, 1f, 1f, 1f)
            ImGui.sameLine()
        }

        ImGui.pushStyleColor(ImGuiCol.Text, col[0], col[1], col[2], col[3])
        ImGui.text("${e.label}   Lvl ${e.level}   xp $xpPct%")
        ImGui.popStyleColor()

        // XP bar tinted to the tier color, clean fill (no overlay).
        ImGui.pushStyleColor(ImGuiCol.PlotHistogram, col[0], col[1], col[2], 1f)
        ImGui.progressBar(e.xpFraction.coerceIn(0f, 1f), -1f, 8f, "")
        ImGui.popStyleColor()
    }

    fun render(skills: List<SkillEntry>) {
        if (skills.isEmpty()) {
            ImGui.textDisabled("No skills.")
            return
        }
        val byCategory = skills.groupBy { it.category }.toSortedMap()
        var first = true
        for ((category, list) in byCategory) {
            if (!first) ImGui.separator()
            first = false
            // Accent header in warm parchment.
            ImGui.pushStyleColor(ImGuiCol.Text, 0.82f, 0.70f, 0.46f, 1f)
            ImGui.text(prettyCategory(category).uppercase())
            ImGui.popStyleColor()
            for (e in list.sortedByDescending { it.value }) skillRow(e)
        }
    }
}
