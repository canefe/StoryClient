package com.canefe.storyclient.client.combat

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

/**
 * Big crosshair-adjacent direction indicator. Visible during Windup, Active,
 * and the first part of Recovery so the player gets a clear "I just swung
 * Left" confirmation. Color-coded:
 *   green  — feint window still open
 *   yellow — committed past feint window
 *   orange — Active (swing landing)
 *   gray   — Recovery (cooldown)
 */
object DirectionCommitHud {
    private const val LABEL_OVERHEAD = "▲ OVERHEAD"
    private const val LABEL_LEFT = "◀ LEFT"
    private const val LABEL_RIGHT = "RIGHT ▶"
    private const val LABEL_THRUST = "✦ THRUST"

    fun render(ctx: DrawContext) {
        val client = MinecraftClient.getInstance()
        client.player ?: return
        val payload = CombatStateClient.getLocal()

        // 1=Windup, 2=Active, 3=Recovery (matches CombatState ordinals on wire)
        val state = payload?.stateOrdinal ?: 0
        val committed = state in 1..3 && payload != null && payload.dirOrdinal in 0..3

        val (dirOrdinal, color) =
            if (committed) {
                val c =
                    when (state) {
                        1 -> if (payload!!.ticksLeft >= 6) 0xFF55FF55.toInt() else 0xFFFFFF55.toInt()
                        2 -> 0xFFFF8C1A.toInt()
                        3 -> 0xFFAAAAAA.toInt()
                        else -> 0xFFFFFFFF.toInt()
                    }
                payload!!.dirOrdinal to c
            } else if (DirectionInputCapture.liveAimEnabled) {
                // Dim white preview — this is the dir the next click would send.
                DirectionInputCapture.liveAimOrdinal to 0xCCCCCCCC.toInt()
            } else {
                return
            }

        val label =
            when (dirOrdinal) {
                0 -> LABEL_OVERHEAD
                1 -> LABEL_LEFT
                2 -> LABEL_RIGHT
                3 -> LABEL_THRUST
                else -> return
            }

        val sw = ctx.scaledWindowWidth
        val sh = ctx.scaledWindowHeight
        val tr = client.textRenderer
        val text = Text.literal(label)
        val textW = tr.getWidth(text)
        val x = sw / 2 - textW / 2
        val y = sh / 2 - 30 // just above the crosshair

        // Matrices push for 1.6x scale so the indicator pops.
        ctx.matrices.push()
        ctx.matrices.translate(x.toFloat(), y.toFloat(), 0f)
        ctx.matrices.scale(1.6f, 1.6f, 1f)
        ctx.drawText(tr, text, 0, 0, color, true)
        ctx.matrices.pop()
    }

}
