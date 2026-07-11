package com.canefe.storyclient.client.confrontation

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW

/**
 * Transparent screen opened while free-text entry is active in a confrontation.
 * Exists only so vanilla dispatches keyPressed/charTyped here and stops feeding
 * movement to the player. [ConfrontationOverlay] draws the actual text via
 * HudRenderCallback. Mirrors [com.canefe.storyclient.client.decision.DecisionFreeformScreen].
 */
class ConfrontationFreeformScreen : Screen(Text.literal("Confrontation Freeform")) {

    override fun shouldPause(): Boolean = false

    override fun shouldCloseOnEsc(): Boolean = false

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // No-op — ConfrontationOverlay renders through HudRenderCallback.
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (!ConfrontationOverlay.freeformMode) {
            closeSelf()
            return true
        }
        when (keyCode) {
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                ConfrontationOverlay.submitFreeform()
                closeSelf()
                return true
            }
            GLFW.GLFW_KEY_ESCAPE -> {
                ConfrontationOverlay.cancelFreeform()
                closeSelf()
                return true
            }
            GLFW.GLFW_KEY_BACKSPACE -> {
                ConfrontationOverlay.backspaceFreeform()
                return true
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(chr: Char, modifiers: Int): Boolean {
        if (!ConfrontationOverlay.freeformMode) {
            closeSelf()
            return true
        }
        ConfrontationOverlay.appendFreeformChar(chr)
        return true
    }

    private fun closeSelf() {
        MinecraftClient.getInstance().setScreen(null)
    }
}
