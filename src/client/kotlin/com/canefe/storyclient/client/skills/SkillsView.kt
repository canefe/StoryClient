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
    private const val MAX_LEVEL = 20

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

    /** A textual pip track: filled up to [level], empty after. ASCII only, since
     *  the loaded Inter font doesn't cover Unicode block glyphs (they'd render as
     *  '?'). Bracketed for a segmented-gauge look. */
    private fun pipTrack(level: Int): String {
        val on = level.coerceIn(0, MAX_LEVEL)
        return "[" + "|".repeat(on) + ".".repeat(MAX_LEVEL - on) + "]"
    }

    private fun skillRow(e: SkillEntry) {
        val frac = (e.value / e.maxValue.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val col = levelColor(frac)
        val xpPct = (e.xpFraction.coerceIn(0f, 1f) * 100).toInt()

        ImGui.pushStyleColor(ImGuiCol.Text, col[0], col[1], col[2], col[3])
        ImGui.text("${e.label}   ${e.value.toInt()}/${e.maxValue.toInt()}   Lvl ${e.level}   xp $xpPct%")
        ImGui.popStyleColor()

        // Pip track in the dimmer tier color.
        ImGui.pushStyleColor(ImGuiCol.Text, col[0] * 0.85f, col[1] * 0.85f, col[2] * 0.85f, 1f)
        ImGui.text(pipTrack(e.level))
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
