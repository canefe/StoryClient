package com.canefe.storyclient.client.skills

import com.canefe.storyclient.client.dm.DMPanelManager
import com.canefe.storyclient.client.health.HealthPanel
import com.canefe.storyclient.client.health.HealthTheme
import imgui.ImGui
import imgui.flag.ImGuiCond
import imgui.type.ImBoolean

/**
 * Standalone Skills window for the LOCAL player. Peer to [HealthPanel] — rendered
 * inside the shared ImGui frame owned by [DMPanelManager], opens independently,
 * and reuses the wood [HealthTheme]. Stacks directly BELOW Health in the top-left
 * column by default.
 *
 * Layout is NOT reset on re-open: a default position/size is suggested only the
 * first time (ImGuiCond.FirstUseEver); after that ImGui remembers whatever the
 * player dragged/resized.
 */
object SkillsPanel {
    private val open = ImBoolean(false)

    /** Set by the right-click "Reset Layout" menu; forces the default next frame. */
    private var resetRequested = false

    private const val DEFAULT_W = 420f
    private const val DEFAULT_H = 400f

    fun isOpen(): Boolean = open.get()

    fun toggle() = setOpen(!open.get())

    fun setOpen(value: Boolean) {
        open.set(value)
    }

    fun render() {
        if (!open.get()) return
        val font = DMPanelManager.interFont
        if (font != null) ImGui.pushFont(font)

        // Default: directly below the Health window (same left column). FirstUseEver
        // so drags stick; forced (Always) on an explicit "Reset Layout".
        val vp = ImGui.getMainViewport()
        val x = vp.posX + HealthPanel.MARGIN
        val y = vp.posY + HealthPanel.MARGIN + HealthPanel.DEFAULT_H + 8f
        val cond = if (resetRequested) ImGuiCond.Always else ImGuiCond.FirstUseEver
        ImGui.setNextWindowPos(x, y, cond)
        ImGui.setNextWindowSize(DEFAULT_W, DEFAULT_H, cond)
        resetRequested = false

        HealthTheme.push()
        if (ImGui.begin("Skills###StorySkills", open)) {
            HealthTheme.drawFrame()
            // Right-click anywhere in the window → context menu with Reset Layout.
            if (ImGui.beginPopupContextWindow()) {
                if (ImGui.menuItem("Reset Layout")) resetRequested = true
                ImGui.endPopup()
            }
            // Guard the content render so a draw error can't skip ImGui.end()
            // (which would imbalance the frame). Log the cause so it's diagnosable
            // instead of silently swallowed by DMPanelManager's runCatching.
            try {
                SkillsView.render(SkillsState.active)
            } catch (t: Throwable) {
                println("[SkillsPanel] render failed: ${t.message}")
                t.printStackTrace()
                ImGui.textDisabled("render error")
            }
        }
        ImGui.end()
        HealthTheme.pop()
        if (font != null) ImGui.popFont()
    }
}
