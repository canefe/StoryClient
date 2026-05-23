package com.canefe.storyclient.client.wheel

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import kotlin.math.max
import kotlin.math.min

/**
 * DM-only inspector for an NPC's perception log. Opened from the action wheel
 * "Perception Log" segment. Re-renders from [PerceptionLogState] each frame so
 * the post-forget refresh appears as soon as the server pushes new data.
 *
 * Layout (per row):
 *   [-]   description
 *         <gray> source · age · distance </gray>
 */
class PerceptionLogScreen(
    private val characterId: String,
    private val labelText: String,
) : Screen(Text.literal("Perception Log — $labelText")) {

    private var scrollY: Int = 0
    private val padX = 24
    private val titleH = 22
    private val footerH = 18
    private val rowVPad = 4
    private val rowGap = 2
    private val btnW = 18
    private val textGap = 10

    /** Per-row geometry cached for this frame: y (in content space) and height. */
    private data class RowGeom(val y: Int, val height: Int, val descLines: List<String>)

    private var rowGeoms: List<RowGeom> = emptyList()

    /** Last time we asked the server for a fresh log. Throttled by [refreshIntervalMs]. */
    private var lastRefreshMs: Long = 0L
    private val refreshIntervalMs = 500L

    override fun shouldPause(): Boolean = false

    override fun init() {
        super.init()
        // Always re-fetch on open so we don't stare at stale cached state.
        PerceptionCommandPayload.request(characterId)
        lastRefreshMs = System.currentTimeMillis()
    }

    /**
     * While the screen is open, poll story-go at a low rate so newly-perceived
     * events appear without the DM having to close and reopen the inspector.
     */
    private fun pollIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastRefreshMs >= refreshIntervalMs) {
            lastRefreshMs = now
            PerceptionCommandPayload.request(characterId)
        }
    }

    private fun listTop(): Int = titleH + 6

    private fun listBottom(): Int = height - footerH - 4

    private fun rows(): List<PerceptionLogEntry> =
        PerceptionLogState.forCharacter(characterId) ?: emptyList()

    private fun textWidth(): Int {
        val btnX = padX
        val textX = btnX + btnW + textGap
        return width - textX - padX - 6 // -6 leaves room for the scrollbar track
    }

    private fun recomputeRowGeoms(list: List<PerceptionLogEntry>, font: net.minecraft.client.font.TextRenderer) {
        val maxW = textWidth().coerceAtLeast(20)
        val out = ArrayList<RowGeom>(list.size)
        var y = 0
        for (e in list) {
            val descSource = e.description.ifBlank { "(no description)" }
            val lines = wrap(font, descSource, maxW)
            val descH = lines.size * font.fontHeight + (lines.size - 1).coerceAtLeast(0) * 1
            val subH = font.fontHeight + 2
            val rowH = rowVPad + descH + subH + rowVPad
            out.add(RowGeom(y, rowH, lines))
            y += rowH + rowGap
        }
        rowGeoms = out
    }

    private fun wrap(font: net.minecraft.client.font.TextRenderer, text: String, maxPx: Int): List<String> {
        if (text.isEmpty()) return listOf("")
        val words = text.split(' ')
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        for (word in words) {
            // Word too long for the column on its own: hard-break it.
            if (font.getWidth(word) > maxPx) {
                if (cur.isNotEmpty()) {
                    lines.add(cur.toString())
                    cur = StringBuilder()
                }
                var rest = word
                while (font.getWidth(rest) > maxPx) {
                    val trimmed = font.trimToWidth(rest, maxPx)
                    if (trimmed.isEmpty()) break
                    lines.add(trimmed)
                    rest = rest.substring(trimmed.length)
                }
                if (rest.isNotEmpty()) cur.append(rest)
                continue
            }
            val candidate = if (cur.isEmpty()) word else "$cur $word"
            if (font.getWidth(candidate) <= maxPx) {
                if (cur.isEmpty()) cur.append(word) else { cur.append(' '); cur.append(word) }
            } else {
                lines.add(cur.toString())
                cur = StringBuilder(word)
            }
        }
        if (cur.isNotEmpty()) lines.add(cur.toString())
        return lines
    }

    private fun contentHeight(): Int = rowGeoms.lastOrNull()?.let { it.y + it.height } ?: 0

    private fun maxScroll(): Int = max(0, contentHeight() - (listBottom() - listTop()))

    private fun clampScroll() {
        scrollY = scrollY.coerceIn(0, maxScroll())
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        renderBackground(context, mouseX, mouseY, delta)
        super.render(context, mouseX, mouseY, delta)

        pollIfDue()

        val font = MinecraftClient.getInstance().textRenderer
        val list = rows()
        recomputeRowGeoms(list, font)
        clampScroll()

        // Title bar
        context.fill(0, 0, width, titleH, 0xCC101418.toInt())
        context.fill(0, titleH, width, titleH + 1, 0xFF2A3340.toInt())
        val titleText = "Perception Log — $labelText"
        context.drawTextWithShadow(font, titleText, padX, 7, 0xFFE6E8EC.toInt())
        val countText = "${list.size} entries"
        context.drawTextWithShadow(
            font,
            countText,
            width - padX - font.getWidth(countText),
            7,
            0xFF8A93A0.toInt(),
        )

        // Empty state
        if (list.isEmpty()) {
            val msg = "(no perceptions cached for this character)"
            context.drawTextWithShadow(
                font,
                msg,
                width / 2 - font.getWidth(msg) / 2,
                height / 2 - font.fontHeight,
                0xFF8A93A0.toInt(),
            )
            renderFooter(context, font)
            return
        }

        // Clip rows to list area so partially-scrolled rows don't bleed onto chrome.
        context.enableScissor(0, listTop(), width, listBottom())

        val nowMs = System.currentTimeMillis()
        val top = listTop()
        list.forEachIndexed { i, entry ->
            val geom = rowGeoms[i]
            val y = top + geom.y - scrollY
            val rowBottom = y + geom.height
            if (rowBottom < listTop() || y > listBottom()) return@forEachIndexed

            val bg = if (i % 2 == 0) 0x66101418.toInt() else 0x66161B22.toInt()
            context.fill(padX - 8, y, width - padX + 8, y + geom.height, bg)

            // [-] remove button (anchored to row top, fixed height)
            val btnX = padX
            val btnY = y + rowVPad
            val btnH = (geom.height - rowVPad * 2).coerceAtMost(18).coerceAtLeast(font.fontHeight + 4)
            val hover = mouseX in btnX..(btnX + btnW) && mouseY in btnY..(btnY + btnH)
            val btnBg = if (hover) 0xFF5A1A1A.toInt() else 0xFF3A1010.toInt()
            val btnBorder = if (hover) 0xFFFF7070.toInt() else 0xFF8A4040.toInt()
            context.fill(btnX, btnY, btnX + btnW, btnY + btnH, btnBg)
            context.fill(btnX, btnY, btnX + btnW, btnY + 1, btnBorder)
            context.fill(btnX, btnY + btnH - 1, btnX + btnW, btnY + btnH, btnBorder)
            context.fill(btnX, btnY, btnX + 1, btnY + btnH, btnBorder)
            context.fill(btnX + btnW - 1, btnY, btnX + btnW, btnY + btnH, btnBorder)
            val sym = "-"
            context.drawTextWithShadow(
                font,
                sym,
                btnX + btnW / 2 - font.getWidth(sym) / 2,
                btnY + (btnH - font.fontHeight) / 2 + 1,
                0xFFFFE0E0.toInt(),
            )

            // Description — wrapped across multiple lines.
            val textX = btnX + btnW + textGap
            var ty = y + rowVPad
            for (line in geom.descLines) {
                context.drawTextWithShadow(font, line, textX, ty, 0xFFE6E8EC.toInt())
                ty += font.fontHeight + 1
            }

            // Subtitle, one line directly below the description block.
            val ageS = max(0L, (nowMs - entry.timestamp) / 1000)
            val ageText =
                when {
                    entry.timestamp <= 0 -> ""
                    ageS < 60 -> "${ageS}s ago"
                    ageS < 3600 -> "${ageS / 60}m ago"
                    else -> "${ageS / 3600}h ago"
                }
            val distText =
                if (entry.distance > 0.0) String.format("%.1fm", entry.distance) else ""
            val sub = buildString {
                append(entry.source.ifBlank { "?" })
                if (ageText.isNotEmpty()) {
                    append(" · "); append(ageText)
                }
                if (distText.isNotEmpty()) {
                    append(" · "); append(distText)
                }
            }
            context.drawTextWithShadow(
                font,
                font.trimToWidth(sub, textWidth()),
                textX,
                ty + 1,
                0xFF8A93A0.toInt(),
            )
        }
        context.disableScissor()

        // Scrollbar
        val totalH = contentHeight()
        val viewH = listBottom() - listTop()
        if (totalH > viewH) {
            val trackX = width - padX + 2
            val trackY = listTop()
            val trackH = viewH
            val thumbH = max(20, trackH * viewH / totalH)
            val thumbY = trackY + ((trackH - thumbH) * scrollY) / maxScroll().coerceAtLeast(1)
            context.fill(trackX, trackY, trackX + 4, trackY + trackH, 0x66000000)
            context.fill(trackX, thumbY, trackX + 4, thumbY + thumbH, 0xFF4A5564.toInt())
        }

        renderFooter(context, font)
    }

    private fun renderFooter(context: DrawContext, font: net.minecraft.client.font.TextRenderer) {
        context.fill(0, height - footerH, width, height, 0xCC101418.toInt())
        context.fill(0, height - footerH - 1, width, height - footerH, 0xFF2A3340.toInt())
        val hint = "Esc to close · scroll to navigate · click [-] to forget"
        context.drawTextWithShadow(
            font,
            hint,
            padX,
            height - footerH + (footerH - font.fontHeight) / 2,
            0xFF8A93A0.toInt(),
        )
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button)
        val list = rows()
        if (list.size != rowGeoms.size) return super.mouseClicked(mouseX, mouseY, button)
        val top = listTop()
        val font = MinecraftClient.getInstance().textRenderer
        list.forEachIndexed { i, _ ->
            val geom = rowGeoms[i]
            val y = top + geom.y - scrollY
            if (y + geom.height < listTop() || y > listBottom()) return@forEachIndexed
            val btnX = padX
            val btnY = y + rowVPad
            val btnH = (geom.height - rowVPad * 2).coerceAtMost(18).coerceAtLeast(font.fontHeight + 4)
            if (mouseX in btnX.toDouble()..(btnX + btnW).toDouble() &&
                mouseY in btnY.toDouble()..(btnY + btnH).toDouble()
            ) {
                // Optimistic local removal so the UI snaps; server will push a
                // fresh log right after which becomes the new source of truth.
                val current = list.toMutableList()
                current.removeAt(i)
                PerceptionLogState.set(characterId, current)
                PerceptionCommandPayload.forget(characterId, i)
                return true
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double,
    ): Boolean {
        val step = 18
        scrollY = (scrollY - verticalAmount.toInt() * step)
        scrollY = min(max(scrollY, 0), maxScroll())
        return true
    }
}
