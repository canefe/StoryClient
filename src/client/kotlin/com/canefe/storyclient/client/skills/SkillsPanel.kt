package com.canefe.storyclient.client.skills

import com.canefe.storyclient.client.dm.DMPanelManager
import com.canefe.storyclient.client.health.HealthTheme
import imgui.ImGui
import imgui.flag.ImGuiCond
import imgui.type.ImBoolean

/**
 * Standalone Skills window for the LOCAL player. Peer to HealthPanel — rendered
 * inside the shared ImGui frame owned by [DMPanelManager], opens independently,
 * and reuses the wood [HealthTheme]. Docks to the RIGHT edge (Health docks left)
 * so both frame the E inventory when opened together.
 *
 * Like HealthPanel, the layout RESETS every open (see [justOpened]) so the panel
 * always comes up in the same place.
 */
object SkillsPanel {
    private val open = ImBoolean(false)

    /** Set on the closed→open edge so [render] re-applies the default layout once. */
    private var justOpened = false

    private const val DEFAULT_W = 420f
    private const val DEFAULT_H = 560f
    private const val MARGIN = 24f

    fun isOpen(): Boolean = open.get()

    fun toggle() = setOpen(!open.get())

    fun setOpen(value: Boolean) {
        if (value && !open.get()) justOpened = true
        open.set(value)
    }

    fun render() {
        if (!open.get()) return
        val font = DMPanelManager.interFont
        if (font != null) ImGui.pushFont(font)

        if (justOpened) {
            val vp = ImGui.getMainViewport()
            // Right edge: viewport right minus panel width minus margin.
            val cx = vp.posX + vp.sizeX - DEFAULT_W - MARGIN
            val cy = vp.posY + (vp.sizeY - DEFAULT_H) * 0.5f
            ImGui.setNextWindowPos(cx, cy, ImGuiCond.Always)
            ImGui.setNextWindowSize(DEFAULT_W, DEFAULT_H, ImGuiCond.Always)
            justOpened = false
        }

        HealthTheme.push()
        if (ImGui.begin("Skills###StorySkills", open)) {
            HealthTheme.drawFrame()
            SkillsView.render(SkillsState.active)
        }
        ImGui.end()
        HealthTheme.pop()
        if (font != null) ImGui.popFont()
    }
}
