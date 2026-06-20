package com.canefe.storyclient.client.permission

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import org.lwjgl.glfw.GLFW

/**
 * Two configurable keybinds for the permission toast. Visible in
 * Options → Controls under the "Story Client" category. Defaults: Y / N.
 *
 * Bindings drive [PermissionToastHud] via the per-tick check in
 * [tickKeybinds]; the active toast (if any) responds to the first
 * wasPressed() that fires, sends the C2S response, and dismisses locally.
 */
object PermissionKeybinds {

    private const val CATEGORY = "key.categories.storyclient"
    private const val ACCEPT_KEY = "key.storyclient.permission.accept"
    private const val DENY_KEY = "key.storyclient.permission.deny"

    private lateinit var accept: KeyBinding
    private lateinit var deny: KeyBinding

    fun register() {
        // Defaults avoid existing StoryClient keybinds: feint=F, wheel=R,
        // live-aim=V, squad-command=Y, dm-panel=J, health=H. accept=G and
        // deny=K sit adjacent for thumb-eye coordination. Rebind in
        // Options → Controls → Story Client.
        accept = KeyBindingHelper.registerKeyBinding(
            KeyBinding(ACCEPT_KEY, InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY),
        )
        deny = KeyBindingHelper.registerKeyBinding(
            KeyBinding(DENY_KEY, InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY),
        )
    }

    /**
     * Per-tick check. Drain `wasPressed()` so a held key fires once.
     * Should be wired from ClientTickEvents.END_CLIENT_TICK in client init.
     */
    fun tickKeybinds() {
        val client = MinecraftClient.getInstance()
        // Don't capture keys when typing in chat / GUI.
        if (client.currentScreen != null) {
            // Still drain so we don't fire when the screen closes.
            while (accept.wasPressed()) {}
            while (deny.wasPressed()) {}
            return
        }
        val active = PermissionToastState.active ?: run {
            while (accept.wasPressed()) {}
            while (deny.wasPressed()) {}
            return
        }
        if (active.dismissingAtMs != null) {
            while (accept.wasPressed()) {}
            while (deny.wasPressed()) {}
            return
        }
        var acceptedFired = false
        var deniedFired = false
        while (accept.wasPressed()) acceptedFired = true
        while (deny.wasPressed()) deniedFired = true
        when {
            acceptedFired -> respond(active.prompt.requestId, accepted = true)
            deniedFired -> respond(active.prompt.requestId, accepted = false)
        }
    }

    private fun respond(requestId: String, accepted: Boolean) {
        PermissionPacketReceiver.sendResponse(requestId, accepted)
        PermissionToastState.dismiss(requestId)
    }

    fun acceptBindingText(): String =
        if (::accept.isInitialized) accept.boundKeyLocalizedText.string else "G"

    fun denyBindingText(): String =
        if (::deny.isInitialized) deny.boundKeyLocalizedText.string else "K"
}
