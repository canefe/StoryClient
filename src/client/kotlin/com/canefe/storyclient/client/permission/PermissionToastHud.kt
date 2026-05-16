package com.canefe.storyclient.client.permission

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import kotlin.math.max
import kotlin.math.min

/**
 * Renders the DM-permission toast pinned to the right edge of the screen.
 * Slides in over [SLIDE_IN_MS], holds while waiting for input or timeout,
 * slides out over [SLIDE_OUT_MS] once dismissed. Below the active toast,
 * pending toasts stack faintly to hint that more are queued.
 *
 * Auto-dismiss-on-timeout is implemented here (HUDs tick every frame on the
 * client). When deadlineMs is reached we mark the toast as dismissing
 * locally — the server-side TaskManager will also auto-refuse, so the
 * eventual PermissionResponseEvent over the WS goes through; the toast is
 * just a parallel UX, not the source of truth.
 */
object PermissionToastHud {

    private const val TOAST_WIDTH = 220
    private const val TOAST_HEIGHT = 56
    private const val MARGIN = 8
    private const val PADDING = 6
    private const val STACK_OFFSET = 6
    private const val SLIDE_IN_MS = 200L
    private const val SLIDE_OUT_MS = 180L

    // Visual constants — tuned to match StoryClient's existing flat-HUD look.
    private const val BG_COLOR = 0xCC141414.toInt()
    private const val BORDER_COLOR = 0xFFFFAA00.toInt()
    private const val TITLE_COLOR = 0xFFFFAA00.toInt()
    private const val TEXT_COLOR = 0xFFEEEEEE.toInt()
    private const val SUBTLE_COLOR = 0xFFAAAAAA.toInt()
    private const val ACCEPT_HINT_COLOR = 0xFF66FF66.toInt()
    private const val DENY_HINT_COLOR = 0xFFFF6666.toInt()
    private const val PROGRESS_BG = 0x66000000.toInt()
    private const val PROGRESS_FG = 0xFFFFAA00.toInt()

    fun render(ctx: DrawContext) {
        val active = PermissionToastState.active ?: return
        val client = MinecraftClient.getInstance()
        val now = System.currentTimeMillis()

        // Auto-dismiss when deadline passes. Server TaskManager will also
        // auto-refuse server-side; we don't send anything from here.
        if (active.dismissingAtMs == null && now >= active.deadlineMs) {
            PermissionToastState.dismiss(active.prompt.requestId)
            return
        }

        val pending = PermissionToastState.pending()
        val sw = ctx.scaledWindowWidth
        val baseY = MARGIN

        val activeOffset = slideOffset(active, now)
        if (active.dismissingAtMs != null && now - (active.dismissingAtMs ?: now) >= SLIDE_OUT_MS) {
            // Slide-out done — the state has already been advanced; nothing to draw for this one.
        } else {
            drawToast(ctx, client, active, sw, baseY, activeOffset, alpha = 1f)
        }

        // Draw queued toasts behind the active one with a subtle stack hint.
        // Limit to 2 visible behind the active one to avoid clutter.
        val maxStack = 2
        var stackedY = baseY + TOAST_HEIGHT + STACK_OFFSET
        for ((idx, toast) in pending.withIndex()) {
            if (idx >= maxStack) break
            drawToast(
                ctx,
                client,
                toast,
                sw,
                stackedY,
                xOffset = 0f,
                alpha = 0.5f - idx * 0.15f,
            )
            stackedY += TOAST_HEIGHT + STACK_OFFSET
        }
    }

    /**
     * Horizontal offset in pixels. 0 = fully on-screen at the right edge.
     * Positive = offscreen (sliding in or out). Smooth ease-out for slide-in.
     */
    private fun slideOffset(toast: PermissionToastState.Toast, now: Long): Float {
        val dismissAt = toast.dismissingAtMs
        if (dismissAt != null) {
            val t = ((now - dismissAt).coerceAtLeast(0L)).toFloat() / SLIDE_OUT_MS
            val clamped = t.coerceIn(0f, 1f)
            // Ease-in for slide-out (accelerates as it leaves).
            return (clamped * clamped) * (TOAST_WIDTH + MARGIN)
        }
        val elapsed = now - toast.shownAtMs
        if (elapsed >= SLIDE_IN_MS) return 0f
        val t = elapsed.toFloat() / SLIDE_IN_MS
        // Ease-out (1 - (1-t)^3): fast start, gentle settle.
        val eased = 1f - (1f - t) * (1f - t) * (1f - t)
        return (1f - eased) * (TOAST_WIDTH + MARGIN)
    }

    private fun drawToast(
        ctx: DrawContext,
        client: MinecraftClient,
        toast: PermissionToastState.Toast,
        screenWidth: Int,
        y: Int,
        xOffset: Float,
        alpha: Float,
    ) {
        val x = screenWidth - TOAST_WIDTH - MARGIN + xOffset.toInt()
        val x2 = x + TOAST_WIDTH
        val y2 = y + TOAST_HEIGHT
        val a = alpha.coerceIn(0f, 1f)

        // Background + border (single thin border line).
        ctx.fill(x, y, x2, y2, applyAlpha(BG_COLOR, a))
        ctx.fill(x, y, x2, y + 1, applyAlpha(BORDER_COLOR, a))
        ctx.fill(x, y2 - 1, x2, y2, applyAlpha(BORDER_COLOR, a))
        ctx.fill(x, y, x + 1, y2, applyAlpha(BORDER_COLOR, a))
        ctx.fill(x2 - 1, y, x2, y2, applyAlpha(BORDER_COLOR, a))

        val tr = client.textRenderer
        val now = System.currentTimeMillis()
        val secsLeft = max(0L, (toast.deadlineMs - now + 999L) / 1000L)

        // Title row: trigger badge on the left, countdown on the right.
        val title = trimToWidth(tr, toast.prompt.trigger, TOAST_WIDTH - PADDING * 2 - 30)
        ctx.drawText(tr, Text.literal(title), x + PADDING, y + PADDING, applyAlpha(TITLE_COLOR, a), false)
        val countdown = "${secsLeft}s"
        val cw = tr.getWidth(countdown)
        ctx.drawText(tr, Text.literal(countdown), x2 - PADDING - cw, y + PADDING, applyAlpha(SUBTLE_COLOR, a), false)

        // Description: up to 2 wrapped lines.
        val maxTextWidth = TOAST_WIDTH - PADDING * 2
        val wrapped = wrapToTwoLines(tr, toast.prompt.description, maxTextWidth)
        val descY = y + PADDING + tr.fontHeight + 2
        for ((i, line) in wrapped.withIndex()) {
            ctx.drawText(tr, Text.literal(line), x + PADDING, descY + i * (tr.fontHeight + 1), applyAlpha(TEXT_COLOR, a), false)
        }

        // Keybind hint row + countdown bar.
        val hintY = y2 - PADDING - tr.fontHeight
        val acceptKey = PermissionKeybinds.acceptBindingText()
        val denyKey = PermissionKeybinds.denyBindingText()
        val acceptText = "[$acceptKey] Accept"
        ctx.drawText(tr, Text.literal(acceptText), x + PADDING, hintY, applyAlpha(ACCEPT_HINT_COLOR, a), false)
        val denyText = "[$denyKey] Deny"
        val denyW = tr.getWidth(denyText)
        ctx.drawText(tr, Text.literal(denyText), x2 - PADDING - denyW, hintY, applyAlpha(DENY_HINT_COLOR, a), false)

        // Countdown bar pinned just above the hint row.
        val totalMs = toast.deadlineMs - (toast.shownAtMs)
        val remainingMs = max(0L, toast.deadlineMs - now)
        val pct = if (totalMs > 0) (remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f) else 0f
        val barY = hintY - 4
        val barX1 = x + PADDING
        val barX2 = x2 - PADDING
        ctx.fill(barX1, barY, barX2, barY + 2, applyAlpha(PROGRESS_BG, a))
        val fillW = ((barX2 - barX1) * pct).toInt()
        if (fillW > 0) {
            ctx.fill(barX1, barY, barX1 + fillW, barY + 2, applyAlpha(PROGRESS_FG, a))
        }
    }

    private fun trimToWidth(tr: net.minecraft.client.font.TextRenderer, s: String, maxWidth: Int): String {
        if (tr.getWidth(s) <= maxWidth) return s
        var t = s
        while (t.isNotEmpty() && tr.getWidth("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }

    private fun wrapToTwoLines(
        tr: net.minecraft.client.font.TextRenderer,
        s: String,
        maxWidth: Int,
    ): List<String> {
        if (s.isEmpty()) return listOf("")
        val words = s.split(' ')
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (w in words) {
            val candidate = if (current.isEmpty()) w else "$current $w"
            if (tr.getWidth(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) lines.add(current.toString())
                current = StringBuilder(w)
                if (lines.size >= 1) break // we're about to start line 3 — trim instead
            }
        }
        if (current.isNotEmpty() && lines.size < 2) lines.add(current.toString())
        // If there was leftover content, ellipsize the last visible line.
        val joinedShown = lines.joinToString(" ")
        if (joinedShown.length < s.length && lines.isNotEmpty()) {
            lines[lines.lastIndex] = trimToWidth(tr, lines.last() + "…", maxWidth)
        }
        return lines.take(2)
    }

    private fun applyAlpha(color: Int, alpha: Float): Int {
        val a = (((color ushr 24) and 0xFF) * alpha.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    // Used by tests / shutdown wiring.
    @Suppress("unused")
    fun minimumPadding() = min(MARGIN, PADDING)
}
