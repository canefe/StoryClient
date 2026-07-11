package com.canefe.storyclient.client.ui

import net.minecraft.client.MinecraftClient
import toni.immersivemessages.ImmersiveFont
import toni.immersivemessages.api.ImmersiveMessage

/**
 * Thin facade over the Immersive Messages API for the client's on-screen
 * messages, so call sites are one-liners instead of raw builder chains and the
 * library stays behind one boundary.
 *
 * All methods grab the local player internally and no-op if absent, so they are
 * safe to call from anywhere. ROBOTO is the house font.
 */
object UIMessages {

    private val font = ImmersiveFont.ROBOTO

    /** Small center-screen line (welcome text, notices). */
    fun sendSmallText(
        text: String,
        duration: Float = 5f,
        color: Int = 0xFFFFFF,
        bold: Boolean = true,
        fadeIn: Float = 0.5f,
        fadeOut: Float = 0.5f,
    ) {
        val player = MinecraftClient.getInstance().player ?: return
        val msg = ImmersiveMessage.builder(duration, text)
            .color(color)
            .font(font)
            .fadeIn(fadeIn)
            .fadeOut(fadeOut)
        if (bold) msg.bold()
        msg.sendLocal(player)
    }

    /**
     * Large title-style center message (chapter/arrival moments, the spawn
     * cinematic). Bigger and longer-lived than [sendSmallText]; slides up in.
     */
    fun sendTitle(
        text: String,
        duration: Float = 4f,
        color: Int = 0xFFFFFF,
        size: Float = 2.0f,
        fadeIn: Float = 0.6f,
        fadeOut: Float = 0.8f,
    ) {
        val player = MinecraftClient.getInstance().player ?: return
        ImmersiveMessage.builder(duration, text)
            .color(color)
            .font(font)
            .bold()
            .size(size)
            .fadeIn(fadeIn)
            .fadeOut(fadeOut)
            .slideUp()
            .sendLocal(player)
    }

    /**
     * Achievement-style tip in the TOP-LEFT corner (the library's "toast"
     * preset): unobtrusive, for reminder-type tips. Uses the real title/subtitle
     * two-tier layout rather than a flattened text block.
     */
    fun toast(
        title: String,
        subtitle: String,
        duration: Float = 6f,
    ) {
        val player = MinecraftClient.getInstance().player ?: return
        ImmersiveMessage.toast(duration, title, subtitle).sendLocal(player)
    }

    /**
     * Centered pop-up above the hotbar (the library's "popup" preset): more
     * intrusive, for tips the player shouldn't miss. Uses the real title/subtitle
     * two-tier layout.
     */
    fun popup(
        title: String,
        subtitle: String,
        duration: Float = 6f,
    ) {
        val player = MinecraftClient.getInstance().player ?: return
        ImmersiveMessage.popup(duration, title, subtitle).sendLocal(player)
    }
}
