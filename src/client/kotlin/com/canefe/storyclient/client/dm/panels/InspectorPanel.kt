package com.canefe.storyclient.client.dm.panels

import com.canefe.storyclient.client.dm.DMPanel
import com.canefe.storyclient.client.dm.DMPanelType
import com.canefe.storyclient.client.wheel.NearbyNPCCache
import imgui.ImGui

object InspectorPanel : DMPanel {
    override val type = DMPanelType.INSPECTOR

    override fun render() {
        if (!type.isOpen()) return
        if (type.begin("###Inspector")) {
            val charId = CharacterListPanel.selected
            if (charId == null) {
                ImGui.textDisabled("Select a character to inspect.")
            } else {
                val entry = NearbyNPCCache.all().firstOrNull { it.characterId == charId }
                if (entry == null) {
                    ImGui.textDisabled("Character $charId no longer in range.")
                } else {
                    ImGui.text("Name:    ${entry.dmLabel}")
                    ImGui.text("Id:      ${entry.characterId}")
                    if (entry.shortLabel.isNotEmpty()) ImGui.text("Short:   ${entry.shortLabel}")
                    if (entry.descriptor.isNotEmpty()) {
                        ImGui.textWrapped("Descriptor: ${entry.descriptor}")
                    }
                    ImGui.separator()
                    if (entry.hp >= 0 && entry.maxHp > 0) {
                        ImGui.text("HP:      ${entry.hp} / ${entry.maxHp}")
                    } else {
                        ImGui.textDisabled("HP not available (DM permission required).")
                    }
                    ImGui.text("Speakable: ${if (entry.canSpeakAs) "yes" else "no"}")
                    ImGui.text("Following: ${if (entry.isFollowing) "yes" else "no"}")
                    ImGui.separator()
                    ImGui.textDisabled("Plan / perceptions: wire via story-go relay (TODO).")
                }
            }
        }
        type.end()
    }
}
