package com.canefe.storyclient.client.health

import com.canefe.storyclient.client.hediff.HediffEntry
import com.canefe.storyclient.client.hediff.HediffHudState
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import kotlin.math.roundToInt

/**
 * Native (DrawContext) port of [HealthView] — the RimWorld-style two-column
 * health body (paper doll | Conditions + Injuries), drawn for the Minecraft-UI
 * Health panel that lives beside the inventory. Replaces the imgui [HealthView].
 *
 * Interaction model (a Screen, not imgui immediate-mode): during [render] we
 * record each visible "Tend" button as a [TendButton] hit-region plus draw a
 * hover tooltip for the bar under the cursor. Click routing is external — the
 * inventory mixin calls [clickAt] on mouseClicked, which fires the matching
 * [TendWoundPayload.tend].
 *
 * Shared, pure grouping/severity logic ([HealthState], [HediffHudState],
 * [MedicineInventory]) is reused verbatim from the imgui path.
 */
object HealthNativeView {
    // --- body layout (px); the frame/title are owned by StoryTabsPanel ---
    private const val DOLL_W = 92         // paper-doll box width (left column)
    private const val COL_GAP = 8
    private const val ROW_LABEL_H = 10
    private const val BAR_H = 8
    private const val ROW_GAP = 4
    private const val TEND_H = 12
    private const val SLOT_SIZE = 18       // medicine slot (and Tend button row height)
    private const val SECTION_GAP = 6

    // --- palette (ARGB) — body colors only (frame colors live in the panel) ---
    private const val PARCHMENT = 0xFFE8D8B8.toInt()
    private const val DIM = 0xFF9A8668.toInt()
    private const val SEP_COL = 0xFF3D2914.toInt()
    private const val BAR_TRACK = 0xFF1A120F.toInt()
    private const val TEND_BG = 0xFF44332E.toInt()
    private const val TEND_BG_HOVER = 0xFF5A463D.toInt()
    private const val TEND_BORDER = 0xFF4F3B34.toInt()
    private const val SLOT_BG = 0xFF1A120F.toInt()  // recessed medicine-slot well

    /** A recorded, clickable Tend button for the current frame. */
    private data class TendButton(
        val x0: Int, val y0: Int, val x1: Int, val y1: Int,
        val woundKey: String, val medicine: String,
    )

    private val buttons = ArrayList<TendButton>()

    /** green (0) → yellow (.5) → red (1) as ARGB for a severity fraction. */
    private fun severityColor(f: Float): Int {
        val c = f.coerceIn(0f, 1f)
        val r = (c * 2f).coerceAtMost(1f)
        val g = (2f - c * 2f).coerceAtMost(1f)
        val ri = (r * 255).roundToInt()
        val gi = (g * 255).roundToInt()
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or 38 // b≈0.15
    }

    private fun prettyPart(part: String): String =
        part.split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    /** Pixel height of the Health BODY (no frame/title), for the panel to size to. */
    fun contentHeight(): Int {
        val entries = HealthState.snapshot()
        val conditions = HealthState.wholeBody(entries)
        val parts = HealthState.byPart(entries)
        var rightH = 10 // "Conditions" header + separator
        rightH += rowsHeight(conditions)
        if (parts.isNotEmpty()) {
            rightH += SECTION_GAP + 10 // "Injuries" header
            for ((_, list) in parts) {
                rightH += ROW_LABEL_H // part name
                rightH += rowsHeight(list)
            }
        }
        val dollH = PaperDollNative.boxHeight(DOLL_W)
        return maxOf(dollH, rightH)
    }

    private fun rowsHeight(list: List<HediffEntry>): Int {
        var h = 0
        for (e in list) {
            h += ROW_LABEL_H + BAR_H + ROW_GAP
            // Tend row (button + medicine slot) shown whenever not fully tended;
            // otherwise a one-line "tended N%" note.
            if (e.tendedQuality < 1f) h += SLOT_SIZE + 2
            else if (e.tendedQuality > 0f) h += ROW_LABEL_H
        }
        return h
    }

    /**
     * Draw the Health BODY into the well at ([x],[y]) with width [w]; the frame
     * and title bar are drawn by [com.canefe.storyclient.client.panel.StoryTabsPanel].
     * [mouseX]/[mouseY] drive hover (tooltips + button highlight). Records Tend
     * buttons for [clickAt].
     */
    fun renderBody(ctx: DrawContext, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int) {
        buttons.clear()
        val client = MinecraftClient.getInstance()
        val tr = client.textRenderer
        val entries = HealthState.snapshot()

        val conditions = HealthState.wholeBody(entries)
        val parts = HealthState.byPart(entries)

        // left column: paper doll (injured parts tinted)
        val dollX = x
        val dollY = y
        PaperDollNative.render(ctx, dollX, dollY, DOLL_W, parts.keys)

        // right column: conditions + injuries
        val rx = dollX + DOLL_W + COL_GAP
        val rx2 = x + w
        var cy = y

        ctx.drawText(tr, "Conditions", rx, cy, PARCHMENT, false)
        cy += 9
        ctx.fill(rx, cy, rx2, cy + 1, SEP_COL)
        cy += 2
        if (conditions.isEmpty()) {
            ctx.drawText(tr, "Healthy.", rx, cy, DIM, false)
            cy += ROW_LABEL_H
        } else {
            for (e in conditions) cy = drawRow(ctx, tr, rx, rx2, cy, e, mouseX, mouseY)
        }

        if (parts.isNotEmpty()) {
            cy += SECTION_GAP
            ctx.drawText(tr, "Injuries", rx, cy, PARCHMENT, false)
            cy += 9
            ctx.fill(rx, cy, rx2, cy + 1, SEP_COL)
            cy += 1
            for ((part, list) in parts) {
                ctx.drawText(tr, prettyPart(part), rx, cy, DIM, false)
                cy += ROW_LABEL_H
                for (e in list) cy = drawRow(ctx, tr, rx, rx2, cy, e, mouseX, mouseY)
            }
        }
    }

    /** One severity row: label, bar+%, optional tended note / Tend button. Returns new cy. */
    private fun drawRow(
        ctx: DrawContext,
        tr: net.minecraft.client.font.TextRenderer,
        rx: Int,
        rx2: Int,
        cyIn: Int,
        e: HediffEntry,
        mouseX: Int,
        mouseY: Int,
    ): Int {
        var cy = cyIn
        val frac = HediffHudState.severityFraction(e)

        ctx.drawText(tr, e.label, rx, cy, PARCHMENT, false)
        cy += ROW_LABEL_H

        // severity bar: track + colored fill + centered %
        ctx.fill(rx, cy, rx2, cy + BAR_H, BAR_TRACK)
        val fillW = ((rx2 - rx) * frac).roundToInt()
        if (fillW > 0) ctx.fill(rx, cy, rx + fillW, cy + BAR_H, severityColor(frac))
        val pct = "${(frac * 100).toInt()}%"
        val pctX = rx + (rx2 - rx - tr.getWidth(pct)) / 2
        ctx.drawText(tr, pct, pctX, cy, 0xFFFFFFFF.toInt(), false)

        // hover tooltip over the bar
        if (mouseX in rx..rx2 && mouseY in cy..(cy + BAR_H)) {
            val lines = buildList {
                add(Text.literal(e.label))
                add(Text.literal("severity ${(frac * 100).toInt()}%"))
                if (e.bodyPart.isNotBlank()) add(Text.literal("part: ${prettyPart(e.bodyPart)}"))
                add(Text.literal("tended ${(e.tendedQuality * 100).toInt()}%"))
            }
            ctx.drawTooltip(tr, lines, mouseX, mouseY)
        }
        cy += BAR_H + ROW_GAP

        if (e.tendedQuality < 1f) {
            // Row: [ Tend button .......... ][ medicine slot ]
            // The medicine slot shows the item a tend would consume (cursor item
            // if held, else best held, else empty = bare-handed). Clicking Tend
            // tends with that medicine, or bare-handed if the slot is empty.
            val choice = MedicineInventory.resolveTend()
            val slotSize = SLOT_SIZE
            val by0 = cy
            val by1 = cy + slotSize
            val slotX0 = rx2 - slotSize
            val btnX1 = slotX0 - 4

            // Tend button (left).
            val btnHovered = mouseX in rx..btnX1 && mouseY in by0..by1
            ctx.fill(rx, by0, btnX1, by1, if (btnHovered) TEND_BG_HOVER else TEND_BG)
            drawBorder(ctx, rx, by0, btnX1, by1, TEND_BORDER)
            val label = if (choice.simId != null) "Tend" else "Tend (bare)"
            val lx = rx + (btnX1 - rx - tr.getWidth(label)) / 2
            ctx.drawText(tr, label, lx, by0 + (slotSize - 8) / 2, PARCHMENT, false)

            // Medicine slot (right): recessed well + item icon (if any).
            val slotHovered = mouseX in slotX0..rx2 && mouseY in by0..by1
            ctx.fill(slotX0, by0, rx2, by1, SLOT_BG)
            drawBorder(ctx, slotX0, by0, rx2, by1, if (slotHovered) TEND_BG_HOVER else TEND_BORDER)
            if (!choice.stack.isEmpty) {
                ctx.drawItem(choice.stack, slotX0 + 1, by0 + 1)
            }
            if (slotHovered) {
                val tip = if (choice.simId != null) "Tend with ${choice.simId}" else "No medicine — hold one to tend with it"
                ctx.drawTooltip(tr, listOf(Text.literal(tip)), mouseX, mouseY)
            }

            val key = if (e.bodyPart.isNotBlank()) "${e.id}@${e.bodyPart}" else e.id
            // The whole row (button + slot) triggers a tend; medicine may be "".
            buttons.add(TendButton(rx, by0, rx2, by1, key, choice.simId ?: ""))
            cy += slotSize + 2
        } else if (e.tendedQuality > 0f) {
            ctx.drawText(tr, "tended ${(e.tendedQuality * 100).toInt()}%", rx, cy, DIM, false)
            cy += ROW_LABEL_H
        }
        return cy
    }

    private fun drawBorder(ctx: DrawContext, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        ctx.fill(x0, y0, x1, y0 + 1, color)
        ctx.fill(x0, y1 - 1, x1, y1, color)
        ctx.fill(x0, y0, x0 + 1, y1, color)
        ctx.fill(x1 - 1, y0, x1, y1, color)
    }

    /**
     * Fire the Tend action for a recorded button under ([mouseX],[mouseY]).
     * Returns true if a button was hit (so the caller can consume the click).
     */
    fun clickAt(mouseX: Int, mouseY: Int): Boolean {
        val b = buttons.firstOrNull { mouseX in it.x0..it.x1 && mouseY in it.y0..it.y1 } ?: return false
        TendWoundPayload.tend(b.woundKey, b.medicine)
        return true
    }
}
