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

    fun render() {
        if (!open.get()) return
        val font = DMPanelManager.interFont
        if (font != null) ImGui.pushFont(font)
        if (ImGui.begin("Health###StoryHealth", open)) {
            val all = HealthState.snapshot()
            val conditions = HealthState.wholeBody(all)
            ImGui.text("Conditions")
            ImGui.separator()
            if (conditions.isEmpty()) {
                ImGui.textDisabled("Healthy.")
            } else {
                for (e in conditions) {
                    val frac = HediffHudState.severityFraction(e)
                    val col = severityColor(frac)
                    ImGui.pushStyleColor(ImGuiCol.PlotHistogram, col[0], col[1], col[2], 1f)
                    ImGui.progressBar(frac, 220f, 16f, "${e.label}  ${(frac * 100).toInt()}%")
                    ImGui.popStyleColor()
                }
            }
        }
        ImGui.end()
        if (font != null) ImGui.popFont()
    }
}
