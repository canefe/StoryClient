package com.canefe.storyclient.client.dm.panels

import com.canefe.storyclient.client.dm.DMPanel
import com.canefe.storyclient.client.dm.DMPanelType
import com.canefe.storyclient.client.wheel.NearbyNPCCache
import imgui.ImGui

object CharacterListPanel : DMPanel {
    override val type = DMPanelType.CHARACTER_LIST

    @Volatile
    var selected: String? = null
        private set

    override fun render() {
        if (!type.isOpen()) return
        if (type.begin("###CharacterList")) {
            val entries = NearbyNPCCache.all()
                .filter { it.entityType == NearbyNPCCache.TYPE_NPC && it.characterId.isNotEmpty() }
                .sortedBy { it.dmLabel.lowercase() }

            ImGui.text("NPCs in range: ${entries.size}")
            ImGui.separator()

            if (entries.isEmpty()) {
                ImGui.textDisabled("No NPCs nearby.")
                if (selected != null) selected = null
            } else {
                if (selected != null && entries.none { it.characterId == selected }) {
                    selected = null
                }
                for (entry in entries) {
                    val label = if (entry.hp >= 0 && entry.maxHp > 0) {
                        "${entry.dmLabel}  (${entry.hp}/${entry.maxHp})"
                    } else {
                        entry.dmLabel
                    }
                    if (ImGui.selectable(label, entry.characterId == selected)) {
                        selected = entry.characterId
                    }
                }
            }
        }
        type.end()
    }
}
