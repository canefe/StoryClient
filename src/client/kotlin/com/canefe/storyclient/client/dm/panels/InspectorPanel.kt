package com.canefe.storyclient.client.dm.panels

import com.canefe.storyclient.client.dm.DMPanel
import com.canefe.storyclient.client.dm.DMPanelType
import com.canefe.storyclient.client.health.HediffWatchPayload
import com.canefe.storyclient.client.health.NeedsView
import com.canefe.storyclient.client.health.NpcNeedsCache
import com.canefe.storyclient.client.wheel.NearbyNPCCache
import imgui.ImGui

/**
 * DM inspector for the selected character, with two tabs:
 *  - Info:  static descriptor + HP / speakable / following.
 *  - Needs: live needs bars (hunger, thirst, …).
 *
 * Needs are not relayed by default, so this panel WATCHES the selection (the same
 * `story:hediff_watch` C2S the Health panel uses): while a character is selected
 * and the panel is open the server pushes that character's needs to
 * [NpcNeedsCache]. Watching is released when nothing is selected or the panel is
 * closed.
 */
object InspectorPanel : DMPanel {
    override val type = DMPanelType.INSPECTOR

    /** characterId currently watched on the server, to diff against selection. */
    private var watched: String? = null

    private fun setWatch(target: String?) {
        if (target == watched) return
        watched = target
        if (target == null) HediffWatchPayload.unwatch() else HediffWatchPayload.watch(target)
    }

    override fun render() {
        if (!type.isOpen()) {
            setWatch(null)
            return
        }
        if (type.begin("###Inspector")) {
            val charId = CharacterListPanel.selected
            setWatch(charId)

            if (charId == null) {
                ImGui.textDisabled("Select a character to inspect.")
            } else {
                val entry = NearbyNPCCache.all().firstOrNull { it.characterId == charId }
                if (ImGui.beginTabBar("###InspectorTabs")) {
                    if (ImGui.beginTabItem("Info")) {
                        renderInfo(charId, entry)
                        ImGui.endTabItem()
                    }
                    if (ImGui.beginTabItem("Needs")) {
                        NeedsView.render(NpcNeedsCache.get(charId))
                        ImGui.endTabItem()
                    }
                    ImGui.endTabBar()
                }
            }
        }
        type.end()
    }

    private fun renderInfo(charId: String, entry: NearbyNPCCache.Entry?) {
        if (entry == null) {
            ImGui.textDisabled("Character $charId no longer in range.")
            return
        }
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
