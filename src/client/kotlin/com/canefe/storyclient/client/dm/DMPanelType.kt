package com.canefe.storyclient.client.dm

import imgui.ImGui
import imgui.type.ImBoolean

/**
 * Registry of every DM Control Panel window, modelled on Axiom's `EditorWindowType`.
 * Each entry owns its open/closed state and the begin/end ImGui calls so panels
 * stay tiny single-purpose `render()` functions.
 */
enum class DMPanelType(
    val nameKey: String,
    val openByDefault: Boolean,
    val extraFlags: Int = 0,
) {
    CHARACTER_LIST("Characters", openByDefault = true),
    INSPECTOR("Inspector", openByDefault = true),
    ACTIVE_PLAN("Active Plan", openByDefault = false),
    ACTION_SENDER("Send Action", openByDefault = false);

    private val open = ImBoolean(openByDefault)
    private var docked = false
    private var focused = false
    private var justOpened = false

    val displayName: String get() = nameKey

    fun isOpen(): Boolean = open.get()

    fun isOpenAndActive(): Boolean = isOpen() && (!docked || focused)

    fun setOpen(value: Boolean) {
        if (open.get() == value) return
        if (value) justOpened = true
        open.set(value)
    }

    /** Pass the same `###Suffix` (stable id) you used at panel creation time. */
    fun begin(suffix: String): Boolean {
        if (justOpened) {
            justOpened = false
        }
        val ok = ImGui.begin("$displayName$suffix", open, extraFlags)
        if (ok) {
            docked = ImGui.isWindowDocked()
            focused = ImGui.isWindowFocused()
        }
        return ok
    }

    fun end() {
        ImGui.end()
    }

    companion object {
        fun openByName(): List<String> =
            entries.filter { it.open.get() }.map { it.nameKey }

        fun setOpenByName(names: List<String>) {
            entries.forEach { it.open.set(names.contains(it.nameKey)) }
        }

        fun resetToDefaults() {
            entries.forEach { it.open.set(it.openByDefault) }
        }
    }
}
