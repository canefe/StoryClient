package com.canefe.storyclient.client.health

import com.canefe.storyclient.client.dm.DMPanelManager
import com.canefe.storyclient.client.hediff.HediffHudState
import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.type.ImBoolean

/**
 * Standalone RimWorld-style Health window for the local player. Rendered inside
 * the shared ImGui frame owned by [DMPanelManager], but NOT docked into the DM
 * dockspace and NOT part of the DM panel list — it floats and opens independently
 * of DM mode via its own keybind (H).
 */
object HealthPanel {
    private val open = ImBoolean(false)

    fun isOpen(): Boolean = open.get()
    fun toggle() = open.set(!open.get())
    fun setOpen(value: Boolean) = open.set(value)

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

    /** Map a wire body-part id to a male-doll piece to tint; null = no visible piece. */
    private fun dollPieceFor(part: String): String? = when (part) {
        "head" -> "head"
        "neck" -> "neck"
        "torso", "chest" -> "torso"
        "left_arm", "right_arm" -> "upperarm"
        "left_hand", "right_hand" -> "hand"
        "left_leg", "right_leg" -> "leg"
        "left_foot", "right_foot" -> "feet"
        else -> null
    }

    private fun prettyPart(part: String): String =
        part.split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    fun render() {
        if (!open.get()) return
        val font = DMPanelManager.interFont
        if (font != null) ImGui.pushFont(font)
        if (ImGui.begin("Health###StoryHealth", open)) {
            val all = HealthState.snapshot()
            val conditions = HealthState.wholeBody(all)
            val parts = HealthState.byPart(all)
            val injured = parts.keys.mapNotNull { dollPieceFor(it) }.toSet()

            ImGui.columns(2, "health_cols", false)
            ImGui.setColumnWidth(0, 150f)
            PaperDoll.render(injured, boxW = 130f)
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
        ImGui.end()
        if (font != null) ImGui.popFont()
    }
}
