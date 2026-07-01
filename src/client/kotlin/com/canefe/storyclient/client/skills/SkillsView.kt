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
        ImGui.text("${e.label}  ${e.value.toInt()}/${e.maxValue.toInt()}   Lvl ${e.level}")
        // Parchment-tone XP fill; frame comes from the theme's FrameBg.
        ImGui.pushStyleColor(ImGuiCol.PlotHistogram, 0.72f, 0.60f, 0.35f, 1f)
        ImGui.progressBar(e.xpFraction.coerceIn(0f, 1f), -1f, 12f, "xp ${(e.xpFraction * 100).toInt()}%")
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
