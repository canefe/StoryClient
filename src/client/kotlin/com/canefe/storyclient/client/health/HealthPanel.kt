package com.canefe.storyclient.client.health

import com.canefe.storyclient.client.dm.DMPanelManager
import imgui.ImGui
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

    fun render() {
        if (!open.get()) return
        val font = DMPanelManager.interFont
        if (font != null) ImGui.pushFont(font)
        if (ImGui.begin("Health###StoryHealth", open)) {
            ImGui.text("Health window — content lands in the next task.")
        }
        ImGui.end()
        if (font != null) ImGui.popFont()
    }
}
