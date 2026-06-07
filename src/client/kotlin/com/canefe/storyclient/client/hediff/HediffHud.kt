package com.canefe.storyclient.client.hediff

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

/**
 * Right-edge vertical column of the local player's active hediff icons
 * (Project-Zomboid style). Each icon is a stage-tinted tile; hovering an icon
 * draws a small tooltip with the label + description.
 *
 * Hooked from the client entry point via Fabric `HudRenderCallback`.
 *
 * NOTE: this repo's yarn mappings expose no `DrawContext.drawTexture` /
 * `drawTooltip` / `setShaderColor` overloads (the combat HUDs all draw with
 * `ctx.fill` + `ctx.drawText`). So the icon body is a stage-tinted filled tile
 * and the tooltip is a hand-drawn `fill`+`drawText` box. Placeholder PNGs do
 * exist under textures/hediff/ for a future textured pass.
 */
object HediffHud {
    private const val ICON = 16
    private const val GAP = 4
    private const val MARGIN_RIGHT = 6
    private const val BORDER_COLOR = 0xFF000000.toInt()

    // Tooltip box styling (mirrors ActionWheelHud's hand-drawn label box).
    private const val TIP_PAD = 4
    private const val TIP_BG = 0xE0000000.toInt()
    private const val TIP_BORDER = 0xFF555555.toInt()
    private const val TIP_LABEL_COLOR = 0xFFFFFFFF.toInt()
    private const val TIP_DESC_COLOR = 0xFFBBBBBB.toInt()

    private fun tintFor(stage: String): Int = when (stage) {
        "Extreme" -> 0xFFFF4040.toInt()
        "Serious" -> 0xFFFF9933.toInt()
        else -> 0xFFFFDD33.toInt()
    }

    fun render(ctx: DrawContext) {
        val client = MinecraftClient.getInstance()
        if (client.player == null) return
        if (client.currentScreen != null) return
        val active = HediffHudState.active
        if (active.isEmpty()) return

        val sw = ctx.scaledWindowWidth
        val sh = ctx.scaledWindowHeight
        val x = sw - ICON - MARGIN_RIGHT
        val totalH = active.size * ICON + (active.size - 1) * GAP
        var y = (sh - totalH) / 2

        val mouse = client.mouse
        val scale = client.window.scaleFactor
        val mx = (mouse.x / scale).toInt()
        val my = (mouse.y / scale).toInt()

        var hovered: HediffEntry? = null
        for (entry in active) {
            drawIcon(ctx, tintFor(entry.stage), x, y)
            if (mx in x..(x + ICON) && my in y..(y + ICON)) hovered = entry
            y += ICON + GAP
        }

        val hov = hovered
        if (hov != null) drawTooltip(ctx, client, hov, mx, my)
    }

    /** Stage-tinted filled tile with a 1px black border. */
    private fun drawIcon(ctx: DrawContext, tint: Int, x: Int, y: Int) {
        ctx.fill(x - 1, y - 1, x + ICON + 1, y + ICON + 1, BORDER_COLOR)
        ctx.fill(x, y, x + ICON, y + ICON, tint)
    }

    private fun drawTooltip(
        ctx: DrawContext,
        client: MinecraftClient,
        entry: HediffEntry,
        mx: Int,
        my: Int,
    ) {
        val tr = client.textRenderer
        val lines = buildList {
            add(Text.literal(entry.label) to TIP_LABEL_COLOR)
            if (entry.description.isNotBlank()) add(Text.literal(entry.description) to TIP_DESC_COLOR)
        }

        val contentW = lines.maxOf { tr.getWidth(it.first) }
        val lineH = tr.fontHeight + 1
        val boxW = contentW + TIP_PAD * 2
        val boxH = lines.size * lineH - 1 + TIP_PAD * 2

        // Anchor to the left of the cursor so it doesn't clip the right edge.
        val sw = ctx.scaledWindowWidth
        var bx = mx - boxW - 6
        if (bx < 2) bx = (mx + 12).coerceAtMost(sw - boxW - 2)
        val by = (my - boxH / 2).coerceIn(2, ctx.scaledWindowHeight - boxH - 2)

        ctx.fill(bx - 1, by - 1, bx + boxW + 1, by + boxH + 1, TIP_BORDER)
        ctx.fill(bx, by, bx + boxW, by + boxH, TIP_BG)

        var ty = by + TIP_PAD
        for ((text, color) in lines) {
            ctx.drawText(tr, text, bx + TIP_PAD, ty, color, false)
            ty += lineH
        }
    }
}
