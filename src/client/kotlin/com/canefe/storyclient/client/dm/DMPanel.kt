package com.canefe.storyclient.client.dm

/**
 * A single dockable DM panel. Implementations should:
 *   1. early-return if `type.isOpen()` is false,
 *   2. call `type.begin("###StableId")`,
 *   3. emit ImGui widgets,
 *   4. call `type.end()` (unconditionally, like ImGui requires).
 *
 * Keep panels stateless where possible — pull data from shared state holders
 * each frame; ImGui is immediate-mode so this is essentially free.
 */
interface DMPanel {
    val type: DMPanelType
    fun render()
}
