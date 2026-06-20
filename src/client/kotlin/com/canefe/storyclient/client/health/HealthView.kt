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
        // PaperDoll also overlays injured internal organs at anatomical spots.
        PaperDoll.render(parts.keys, boxW = 130f)
        ImGui.nextColumn()

        ImGui.text("Conditions")
        ImGui.separator()
        if (conditions.isEmpty()) {
            ImGui.textDisabled("Healthy.")
        } else {
            for (e in conditions) {
                severityBar(e.label, HediffHudState.severityFraction(e))
                // Tooltip on hover: show wound details over the severity bar.
                if (ImGui.isItemHovered()) {
                    ImGui.beginTooltip()
                    ImGui.text(e.label)
                    ImGui.text("severity ${(HediffHudState.severityFraction(e) * 100).toInt()}%")
                    if (e.bodyPart.isNotBlank()) ImGui.text("part: ${prettyPart(e.bodyPart)}")
                    ImGui.text("tended ${(e.tendedQuality * 100).toInt()}%")
                    ImGui.endTooltip()
                }
                if (e.tendedQuality > 0f) {
                    ImGui.sameLine()
                    ImGui.text("tended ${(e.tendedQuality * 100).toInt()}%")
                }
                // Tend button: gated on holding medicine + wound not fully tended.
                val medicine = MedicineInventory.bestHeld()
                if (medicine != null && e.tendedQuality < 1f) {
                    ImGui.sameLine()
                    if (ImGui.button("Tend##${e.id}_${e.bodyPart}")) {
                        val key = if (e.bodyPart.isNotBlank()) "${e.id}@${e.bodyPart}" else e.id
                        TendWoundPayload.tend(key, medicine)
                    }
                }
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
