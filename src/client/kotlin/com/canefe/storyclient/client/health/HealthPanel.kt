package com.canefe.storyclient.client.health

import com.canefe.storyclient.client.dm.DMPanelManager
import imgui.ImGui
import imgui.flag.ImGuiCond
import imgui.type.ImBoolean

/**
 * Standalone RimWorld-style Health window for the LOCAL player. Rendered inside
 * the shared ImGui frame owned by [DMPanelManager], but NOT docked into the DM
 * dockspace and NOT part of the DM panel list — it floats and opens independently
 * of DM mode via its own keybind (H). The body layout is shared with the DM
 * selected-character panel via [HealthView].
 *
 * Layout is NOT reset on re-open: we suggest a default position/size only the
 * first time (ImGuiCond.FirstUseEver), then ImGui remembers whatever the player
 * dragged/resized in its `.ini`. Health docks to the TOP-LEFT; [SkillsPanel]
 * stacks directly below it.
 */
object HealthPanel {
    private val open = ImBoolean(false)

    /** Default window size (px), suggested on first use only. */
    const val DEFAULT_W = 420f
    const val DEFAULT_H = 360f

    /** Left/top margin (px) from the viewport edge for the default position. */
    const val MARGIN = 12f

    fun isOpen(): Boolean = open.get()

    fun toggle() = setOpen(!open.get())

    fun setOpen(value: Boolean) {
        open.set(value)
    }

    fun render() {
        if (!open.get()) return
        val font = DMPanelManager.interFont
        if (font != null) ImGui.pushFont(font)

        // First-time default only: top-left. FirstUseEver means ImGui applies
        // this once, then honors any position/size the player drags thereafter.
        val vp = ImGui.getMainViewport()
        ImGui.setNextWindowPos(vp.posX + MARGIN, vp.posY + MARGIN, ImGuiCond.FirstUseEver)
        ImGui.setNextWindowSize(DEFAULT_W, DEFAULT_H, ImGuiCond.FirstUseEver)

        // Scoped medieval/Minecraft-inventory theme — Health window only.
        HealthTheme.push()
        if (ImGui.begin("Health###StoryHealth", open)) {
            HealthTheme.drawFrame()
            HealthView.render(HealthState.snapshot())
        }
        ImGui.end()
        HealthTheme.pop()
        if (font != null) ImGui.popFont()
    }
}
