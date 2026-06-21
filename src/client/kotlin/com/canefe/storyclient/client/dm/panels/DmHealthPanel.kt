package com.canefe.storyclient.client.dm.panels

import com.canefe.storyclient.client.dm.DMPanel
import com.canefe.storyclient.client.dm.DMPanelType
import com.canefe.storyclient.client.health.HealthView
import com.canefe.storyclient.client.health.HediffWatchPayload
import com.canefe.storyclient.client.health.NeedsView
import com.canefe.storyclient.client.health.NpcHediffCache
import com.canefe.storyclient.client.health.NpcNeedsCache
import com.canefe.storyclient.client.wheel.NearbyNPCCache
import imgui.ImGui

/**
 * DM panel showing the SELECTED character's health (conditions + per-body-part
 * injuries + paper-doll), reusing [HealthView]. The selected NPC's hediffs are
 * not relayed by default, so this panel WATCHES the selection: when the selected
 * characterId changes it sends a `story:hediff_watch` request and the server
 * (DM-gated) pushes that NPC's hediffs to [NpcHediffCache]. Watching is released
 * when nothing is selected or the panel is closed.
 */
object DmHealthPanel : DMPanel {
    override val type = DMPanelType.DM_HEALTH

    /** characterId currently watched on the server, to diff against selection. */
    private var watched: String? = null

    private fun setWatch(target: String?) {
        if (target == watched) return
        watched = target
        if (target == null) {
            HediffWatchPayload.unwatch()
        } else {
            HediffWatchPayload.watch(target)
        }
    }

    override fun render() {
        if (!type.isOpen()) {
            // Panel closed: stop watching so the server isn't pushing for nothing.
            setWatch(null)
            return
        }
        if (type.begin("###DmHealth")) {
            val selected = CharacterListPanel.selected
            setWatch(selected)

            if (selected == null) {
                ImGui.textDisabled("Select a character to view their health.")
            } else {
                val entry = NearbyNPCCache.all().firstOrNull { it.characterId == selected }
                val label = entry?.dmLabel ?: selected
                ImGui.text(label)
                if (entry != null && entry.hp >= 0 && entry.maxHp > 0) {
                    ImGui.sameLine()
                    ImGui.textDisabled("(${entry.hp}/${entry.maxHp})")
                }
                ImGui.separator()
                ImGui.text("Needs")
                NeedsView.render(NpcNeedsCache.get(selected))
                ImGui.separator()
                HealthView.render(NpcHediffCache.get(selected))
            }
        }
        type.end()
    }
}
