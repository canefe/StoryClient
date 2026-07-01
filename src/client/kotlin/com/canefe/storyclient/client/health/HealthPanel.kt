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

    /** Set by the right-click "Reset Layout" menu; forces the default next frame. */
    private var resetRequested = false

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

        // Default position/size: FirstUseEver so ImGui applies it once then honors
        // the player's drags. On an explicit "Reset Layout" we force it (Always).
        val vp = ImGui.getMainViewport()
        val cond = if (resetRequested) ImGuiCond.Always else ImGuiCond.FirstUseEver
        ImGui.setNextWindowPos(vp.posX + MARGIN, vp.posY + MARGIN, cond)
        ImGui.setNextWindowSize(DEFAULT_W, DEFAULT_H, cond)
        resetRequested = false

        // Scoped medieval/Minecraft-inventory theme — Health window only.
        HealthTheme.push()
        if (ImGui.begin("Health###StoryHealth", open)) {
            HealthTheme.drawFrame()
            // Right-click anywhere in the window → context menu with Reset Layout.
            if (ImGui.beginPopupContextWindow()) {
                if (ImGui.menuItem("Reset Layout")) resetRequested = true
                ImGui.endPopup()
            }
            HealthView.render(HealthState.snapshot())
        }
        ImGui.end()
        HealthTheme.pop()
        if (font != null) ImGui.popFont()
    }
}
