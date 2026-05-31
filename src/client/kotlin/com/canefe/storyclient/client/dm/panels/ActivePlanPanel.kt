package com.canefe.storyclient.client.dm.panels

import com.canefe.storyclient.client.dm.DMPanel
import com.canefe.storyclient.client.dm.DMPanelType
import com.canefe.storyclient.client.wheel.NearbyNPCCache
import imgui.ImGui

object ActivePlanPanel : DMPanel {
    override val type = DMPanelType.ACTIVE_PLAN

    override fun render() {
        if (!type.isOpen()) return
        if (type.begin("###ActivePlan")) {
            val charId = CharacterListPanel.selected
            if (charId == null) {
                ImGui.textDisabled("Select a character to see its plan.")
            } else {
                val label = NearbyNPCCache.all().firstOrNull { it.characterId == charId }?.dmLabel ?: charId
                ImGui.text("$label's plan tree:")
                ImGui.separator()
                ImGui.textDisabled("(awaiting story-sim BRP feed)")
            }
        }
        type.end()
    }
}
