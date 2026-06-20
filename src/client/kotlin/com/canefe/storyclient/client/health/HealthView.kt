package com.canefe.storyclient.client.health

import com.canefe.storyclient.client.hediff.HediffEntry
import com.canefe.storyclient.client.hediff.HediffHudState
import imgui.ImGui
import imgui.flag.ImGuiCol

/**
 * Shared RimWorld-style health body: paper-doll + whole-body Conditions +
 * per-body-part Injuries, for ANY hediff list. Both the local-player window
 * ([HealthPanel]) and the DM selected-character panel render through here, so
 * the layout/coloring lives in exactly one place.
 */
object HealthView {
    /** green (0) → yellow (.5) → red (1) RGB for a severity fraction. */
    private fun severityColor(f: Float): FloatArray {
        val c = f.coerceIn(0f, 1f)
        val r = (c * 2f).coerceAtMost(1f)
        val g = (2f - c * 2f).coerceAtMost(1f)
        return floatArrayOf(r, g, 0.15f)
    }

    /**
     * One labeled severity row: the name on its own line, then a color-coded bar
     * whose only overlay is the percentage. Keeping the name out of the bar
     * avoids the label colliding with the fill at low fractions.
     */
    private fun severityBar(label: String, frac: Float) {
        ImGui.text(label)
        val col = severityColor(frac)
        ImGui.pushStyleColor(ImGuiCol.PlotHistogram, col[0], col[1], col[2], 1f)
        ImGui.progressBar(frac, -1f, 14f, "${(frac * 100).toInt()}%")
        ImGui.popStyleColor()
    }

    private fun prettyPart(part: String): String =
        part.split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    /** Render the two-column health body (doll | conditions+injuries) for [entries]. */
    fun render(entries: List<HediffEntry>) {
        val conditions = HealthState.wholeBody(entries)
        val parts = HealthState.byPart(entries)

        ImGui.columns(2, "health_cols", false)
        ImGui.setColumnWidth(0, 150f)
        // Raw injured wire-part ids; PaperDoll maps each to the correct
        // side-specific piece (left_arm tints only the left arm).
        PaperDoll.render(parts.keys, boxW = 130f)
        ImGui.nextColumn()

        ImGui.text("Conditions")
        ImGui.separator()
        if (conditions.isEmpty()) {
            ImGui.textDisabled("Healthy.")
        } else {
            for (e in conditions) {
                severityBar(e.label, HediffHudState.severityFraction(e))
            }
        }

        if (parts.isNotEmpty()) {
            ImGui.separator()
            ImGui.text("Injuries")
            for ((part, list) in parts) {
                ImGui.textDisabled(prettyPart(part))
                for (e in list) {
                    severityBar(e.label, HediffHudState.severityFraction(e))
                }
            }
        }
        ImGui.columns(1)
    }
}
