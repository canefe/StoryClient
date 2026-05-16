package com.canefe.storyclient.client.combat

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

/**
 * 0.5s fade banner across the centre of the screen showing the outcome of
 * the most recent swing involving the local player ("PARRIED", "BLOCKED",
 * "STAGGERED", etc). Also displays IntentRejected reasons.
 */
object OutcomeBannerHud {
    private const val BANNER_TICKS = 10 // 0.5s @ 20 tps

    @Volatile private var ticksLeft = 0

    @Volatile private var label = ""

    @Volatile private var color = 0xFFFFFFFF.toInt()

    fun onOutcome(payload: HitOutcomePayload) {
        val client = MinecraftClient.getInstance()
        val localId = client.player?.id ?: return
        if (payload.attackerEntityId != localId && payload.defenderEntityId != localId) return

        val (text, c) =
            when (payload.outcomeOrdinal) {
                0 -> "HIT" to 0xFFFF5555.toInt()
                1 -> "PARRIED" to 0xFF55FF55.toInt()
                2 -> "PERFECT BLOCK" to 0xFF55AAFF.toInt()
                3 -> "BLOCKED" to 0xFFAAAAFF.toInt()
                4 -> "BAD BLOCK" to 0xFFFF8855.toInt()
                else -> "?" to 0xFFFFFFFF.toInt()
            }
        // Personalize label by perspective.
        label =
            if (payload.attackerEntityId == localId && payload.outcomeOrdinal == 1) "PARRIED!" else text
        color = c
        ticksLeft = BANNER_TICKS
    }

    fun onRejected(reason: String) {
        label = reason.uppercase()
        color = 0xFFFF5555.toInt()
        ticksLeft = BANNER_TICKS
    }

    fun tick() {
        if (ticksLeft > 0) ticksLeft--
    }

    fun render(ctx: DrawContext) {
        if (ticksLeft <= 0) return
        val client = MinecraftClient.getInstance()
        val tr = client.textRenderer
        val sw = ctx.scaledWindowWidth
        val sh = ctx.scaledWindowHeight
        val text = Text.literal(label)
        val w = tr.getWidth(text)
        val x = (sw - w) / 2
        val y = sh / 2 - 40
        ctx.drawText(tr, text, x, y, color, true)
    }
}
