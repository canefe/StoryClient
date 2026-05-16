package com.canefe.storyclient.client.decision

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

/**
 * Transparent screen opened while [DecisionState.freeformMode] is true.
 *
 * The screen exists solely so vanilla input dispatches keyPressed / charTyped to us
 * (and, equally important, stops dispatching movement to the player). The HUD is
 * still drawn by [DecisionHud] via HudRenderCallback; this screen renders nothing.
 */
class DecisionFreeformScreen : Screen(Text.literal("Decision Freeform")) {

    override fun shouldPause(): Boolean = false

    override fun shouldCloseOnEsc(): Boolean = false

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // No-op — DecisionHud renders the actual UI through HudRenderCallback.
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (!DecisionState.freeformMode) {
            closeSelf()
            return true
        }
        if (DecisionHud.handleKeyPress(keyCode)) {
            // Submit/cancel may have ended freeform mode — close if so.
            if (!DecisionState.freeformMode) closeSelf()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(chr: Char, modifiers: Int): Boolean {
        if (!DecisionState.freeformMode) {
            closeSelf()
            return true
        }
        DecisionHud.appendFreeformChar(chr)
        return true
    }

    private fun closeSelf() {
        MinecraftClient.getInstance().setScreen(null)
    }
}

