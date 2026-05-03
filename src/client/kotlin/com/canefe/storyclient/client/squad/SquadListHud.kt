package com.canefe.storyclient.client.squad

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext

/**
 * Right-side sidebar listing all commandable squads. Visible only while
 * [SquadCommandState.commandMode] is true.
 *
 *   ▢ 1 ⬤ Cavalry      [3]  Hold
 *   ▣ 2 ⬤ Spearmen     [5]  Move
 *   ▢ 3 ⬤ Archers      [4]  Idle
 *
 * Filled box = selected. Outline-only box = not selected. Number = hotkey.
 */
object SquadListHud {
    private const val PAD = 4
    private const val ROW_HEIGHT = 14

    fun render(ctx: DrawContext) {
        if (!SquadCommandState.commandMode) return
        val entries = SquadListCache.entries
        if (entries.isEmpty()) {
            renderEmptyHint(ctx)
            return
        }

        val client = MinecraftClient.getInstance()
        val font = client.textRenderer
        val w = client.window.scaledWidth
        val h = client.window.scaledHeight

        // Compute the widest label so the sidebar has a stable width.
        val maxText = entries.maxOf { font.getWidth(rowText(it)) }
        val sidebarWidth = maxText + 36 + PAD * 2
        val sidebarHeight = entries.size * ROW_HEIGHT + PAD * 2 + 12

        val x = w - sidebarWidth - 8
        val y = (h - sidebarHeight) / 2

        // Background
        ctx.fill(x, y, x + sidebarWidth, y + sidebarHeight, 0xCC1A1A1A.toInt())
        // Header
        val header = "Command Mode"
        ctx.drawTextWithShadow(font, header, x + PAD, y + PAD, 0xFFAAFFAA.toInt())

        var rowY = y + PAD + 12
        entries.forEachIndexed { i, entry ->
            val ordinal = i + 1
            val selected = SquadCommandState.selectedSquadIds.contains(entry.id)
            renderRow(ctx, x + PAD, rowY, sidebarWidth - PAD * 2, ordinal, entry, selected)
            rowY += ROW_HEIGHT
        }
    }

    private fun renderEmptyHint(ctx: DrawContext) {
        val client = MinecraftClient.getInstance()
        val font = client.textRenderer
        val w = client.window.scaledWidth
        val h = client.window.scaledHeight
        val text = "No squads to command"
        val textWidth = font.getWidth(text)
        val x = w - textWidth - 16
        val y = h / 2 - font.fontHeight / 2
        ctx.fill(x - 4, y - 2, x + textWidth + 4, y + font.fontHeight + 2, 0xCC1A1A1A.toInt())
        ctx.drawTextWithShadow(font, text, x, y, 0xFFAAAAAA.toInt())
    }

    private fun renderRow(
        ctx: DrawContext,
        x: Int,
        y: Int,
        width: Int,
        ordinal: Int,
        entry: SquadListCache.Entry,
        selected: Boolean,
    ) {
        val font = MinecraftClient.getInstance().textRenderer
        val colorWithAlpha = 0xFF000000.toInt() or entry.color

        // Selection border in squad color
        if (selected) {
            ctx.fill(x - 1, y - 1, x + width + 1, y + ROW_HEIGHT - 1, colorWithAlpha)
            ctx.fill(x, y, x + width, y + ROW_HEIGHT - 2, 0xFF111111.toInt())
        }

        // Hotkey number
        val numStr = ordinal.toString()
        ctx.drawTextWithShadow(font, numStr, x + 2, y + 2, 0xFFFFFFFF.toInt())

        // Color swatch
        val swatchX = x + 12
        val swatchY = y + 3
        ctx.fill(swatchX, swatchY, swatchX + 8, swatchY + 8, colorWithAlpha)

        // Row text
        val text = rowText(entry)
        val textColor = if (selected) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt()
        ctx.drawTextWithShadow(font, text, x + 24, y + 2, textColor)
    }

    private fun rowText(e: SquadListCache.Entry): String =
        "${e.name} [${e.memberCount}]  ${e.orderLabel}/${e.formationLabel}"
}
