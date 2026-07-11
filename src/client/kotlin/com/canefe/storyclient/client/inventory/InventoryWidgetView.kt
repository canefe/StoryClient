package com.canefe.storyclient.client.inventory

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Identifier

/**
 * Renders [InventoryWidgetState] rows into the freed-up crafting-grid corner of
 * the survival inventory. Called from InventoryCraftingHiderMixin's foreground
 * hook with the panel-local origin of the paint-over rect.
 *
 * Display-only, content-agnostic: draws each row as optional icon + label +
 * right-aligned value, with an optional thin meter bar beneath rows that carry
 * one. Rows are clamped to the available height (extra rows dropped silently).
 */
object InventoryWidgetView {

    private const val ROW_HEIGHT = 12       // label line + gap
    private const val BAR_HEIGHT = 2
    private const val ICON_SIZE = 16
    private const val VALUE_COLUMN = 34     // value x-offset from label start (past widest label)
    private const val LABEL_COLOR = 0xFFE8D8C0.toInt()
    private const val VALUE_COLOR = 0xFFFFFFFF.toInt()
    private const val BAR_BG_COLOR = 0xFF20160F.toInt()
    private const val BAR_FG_COLOR = 0xFFC8A24A.toInt()

    /**
     * @param x,y  panel-local top-left of the widget region
     * @param w,h  region size (panel-local); content is clamped to it
     */
    fun render(context: DrawContext, x: Int, y: Int, w: Int, h: Int) {
        val rows = InventoryWidgetState.rows
        if (rows.isEmpty()) return

        val tr = MinecraftClient.getInstance().textRenderer
        var cursorY = y

        for (row in rows) {
            // Stop before overflowing the region (need room for a row + its bar).
            val rowSpan = ROW_HEIGHT + if (row.bar != null) BAR_HEIGHT + 1 else 0
            if (cursorY + rowSpan > y + h) break

            var textX = x
            if (row.icon.isNotEmpty()) {
                drawIcon(context, row.icon, x, cursorY)
                textX = x + ICON_SIZE + 2
            }

            // Label left-aligned; value left-aligned in a shared column just past
            // the widest label, so values line up without hugging the right edge.
            if (row.label.isNotEmpty()) {
                context.drawText(tr, Text.literal(row.label), textX, cursorY + 1, LABEL_COLOR, false)
            }
            if (row.value.isNotEmpty()) {
                context.drawText(tr, Text.literal(row.value), textX + VALUE_COLUMN, cursorY + 1, VALUE_COLOR, false)
            }

            var next = cursorY + ROW_HEIGHT
            val bar = row.bar
            if (bar != null) {
                val fill = bar.coerceIn(0f, 1f)
                val barTop = next - 1
                context.fill(x, barTop, x + w, barTop + BAR_HEIGHT, BAR_BG_COLOR)
                context.fill(x, barTop, x + (w * fill).toInt(), barTop + BAR_HEIGHT, BAR_FG_COLOR)
                next = barTop + BAR_HEIGHT + 1
            }
            cursorY = next
        }
    }

    /**
     * Resolve [icon] to an item and draw it. Accepts a bare item id ("gold_nugget")
     * or a namespaced id ("minecraft:gold_nugget"). Unknown ids draw nothing.
     */
    private fun drawIcon(context: DrawContext, icon: String, x: Int, y: Int) {
        val id = runCatching { Identifier.of(icon) }.getOrNull() ?: return
        val item = Registries.ITEM.getOrEmpty(id).orElse(null) ?: return
        context.drawItem(item.defaultStack, x, y - 3)
    }
}
