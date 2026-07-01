package com.canefe.storyclient.client.skills

import imgui.ImGui
import imgui.flag.ImGuiCol

/**
 * Renders the local player's skills grouped by category. Each row: name,
 * competence value / max, display level (floor(value/5)), and an XP-to-next
 * progress bar. The bar frame comes from the caller's theme (FrameBg); only
 * the fill color is set here.
 */
object SkillsView {
    private fun prettyCategory(c: String): String =
        if (c.isBlank()) "Other"
        else c.split('_').joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }

    private fun skillRow(e: SkillEntry) {
        val xpPct = (e.xpFraction.coerceIn(0f, 1f) * 100).toInt()
        // Name, competence, level, and XP% all on the label line so nothing
        // overflows/clips the bar. The bar itself is a clean fill (no overlay),
        // inset slightly from the right frame so it never touches the wood edge.
        ImGui.text("${e.label}  ${e.value.toInt()}/${e.maxValue.toInt()}   Lvl ${e.level}   xp $xpPct%")
        // -1 = fill the content width (already inset by the window padding, so it
        // won't touch the wood frame). No overlay text — the % is on the label
        // line above, which avoids the centered-overlay clipping at low fills.
        ImGui.pushStyleColor(ImGuiCol.PlotHistogram, 0.72f, 0.60f, 0.35f, 1f)
        ImGui.progressBar(e.xpFraction.coerceIn(0f, 1f), -1f, 10f, "")
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
            ImGui.text(prettyCategory(category))
            for (e in list.sortedByDescending { it.value }) skillRow(e)
        }
    }
}
