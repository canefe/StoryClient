package com.canefe.storyclient.client.dm.panels

import com.canefe.storyclient.client.dm.DMPanel
import com.canefe.storyclient.client.dm.DMPanelType
import com.canefe.storyclient.client.puppet.PuppetCommandPayload
import com.canefe.storyclient.client.puppet.PuppetState
import com.canefe.storyclient.client.wheel.NearbyNPCCache
import imgui.ImGui
import imgui.type.ImString

object ActionSenderPanel : DMPanel {
    override val type = DMPanelType.ACTION_SENDER

    private val speakBuffer = ImString(256)

    override fun render() {
        if (!type.isOpen()) return
        if (type.begin("###ActionSender")) {
            val charId = CharacterListPanel.selected
            if (charId == null) {
                ImGui.textDisabled("Select a character first.")
            } else {
                val label = NearbyNPCCache.all().firstOrNull { it.characterId == charId }?.dmLabel ?: charId
                ImGui.text("Target: $label  ($charId)")
                ImGui.separator()

                ImGui.inputText("Say", speakBuffer)
                val sendDisabled = speakBuffer.get().isBlank()
                if (sendDisabled) ImGui.beginDisabled()
                if (ImGui.button("Send npc.speak")) {
                    PuppetCommandPayload.speakAs(charId, speakBuffer.get())
                    speakBuffer.set("")
                }
                if (sendDisabled) ImGui.endDisabled()

                ImGui.separator()
                val isGrabbed = PuppetState.grabbedCharacterIds.contains(charId)
                if (ImGui.button(if (isGrabbed) "Release" else "Grab")) {
                    val nowGrabbed = PuppetState.localGrabToggle(charId)
                    PuppetCommandPayload.dmControl(charId, nowGrabbed)
                }
            }
        }
        type.end()
    }
}
