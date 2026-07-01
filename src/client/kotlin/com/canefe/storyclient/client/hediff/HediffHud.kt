package com.canefe.storyclient.client.hediff

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import net.minecraft.util.Identifier

/**
 * Right-edge vertical column of the local player's active hediff icons
 * (Project-Zomboid style). Each icon is a per-hediff PNG, severity-tinted;
 * hovering an icon (while a screen frees the cursor) draws a small tooltip with
 * the label + description.
 *
 * Hooked from the client entry point via Fabric `HudRenderCallback`.
 *
 * Textures: assets/storyclient/textures/hediff/<id>.png, with unknown.png as a
 * fallback for ids that ship no art. Drawn via the 1.21.1 `drawTexture(Identifier,
 * x, y, u, v, w, h, texW, texH)` overload, tinted by `setShaderColor` so stage
 * still reads through color. The tooltip stays a hand-drawn `fill`+`drawText`
 * box (mirrors ActionWheelHud).
 */
object HediffHud {
    private const val ICON = 16     // on-screen icon size (px)
    private const val TEX_SIZE = 32 // source PNG dimensions under textures/hediff/
    private const val PAD = 3       // backdrop padding around the icon (px)

    // DynamicMoodles-style tile: dark rounded panel, icon on the left + a vertical
    // segmented severity gauge on the right.
    private const val GAUGE_W = 3   // gauge column width (px)
    private const val GAUGE_GAP = 2 // gap between icon and gauge (px)
    private const val SEGMENTS = 4  // number of gauge segments
    private const val TILE_W = ICON + PAD * 2 + GAUGE_GAP + GAUGE_W // full tile width
    private const val TILE_H = ICON + PAD * 2                       // full tile height
    private const val GAP = 4
    private const val MARGIN_RIGHT = 6

    // Flat dark tile (PZ-style), not the old wood button.
    private const val TILE_BG = 0xE0181410.toInt()       // near-black translucent
    private const val TILE_BORDER = 0xFF3D2914.toInt()   // dark brown edge
    private const val SEG_OFF = 0xFF2A2A2A.toInt()        // empty gauge segment

    private val FALLBACK_TEX = Identifier.of("storyclient", "textures/hediff/unknown.png")

    /** Icon folder by entry kind: hediffs vs moodlets ship art in separate dirs. */
    private fun textureFor(entry: HediffEntry): Identifier {
        val dir = if (entry.kind == "moodlet") "moodlet" else "hediff"
        return Identifier.of("storyclient", "textures/$dir/${entry.id}.png")
    }

    // Tooltip box styling (mirrors ActionWheelHud's hand-drawn label box).
    private const val TIP_PAD = 4
    private const val TIP_MAX_WIDTH = 180 // px; description wraps within this
    private const val TIP_BG = 0xE0000000.toInt()
    private const val TIP_BORDER = 0xFF555555.toInt()
    private const val TIP_LABEL_COLOR = 0xFFFFFFFF.toInt()
    private const val TIP_DESC_COLOR = 0xFFBBBBBB.toInt()

    // Severity color, used to fill the circular backdrop behind each icon.
    private fun tintFor(stage: String): Int = when (stage) {
        "Extreme" -> 0xFFFF4040.toInt()
        "Serious" -> 0xFFFF9933.toInt()
        else -> 0xFFFFDD33.toInt()
    }

    fun render(ctx: DrawContext) {
        val client = MinecraftClient.getInstance()
        if (client.player == null) return
        val snapshot = HediffHudState.renderSnapshot()
        if (snapshot.isEmpty()) return

        // Hover only resolves when a screen is open (chat/inventory), because in
        // normal first-person play the cursor is grabbed and centered. We keep
        // the icon column visible in both states; the tooltip is reachable while
        // a screen holds the cursor free.
        val screenOpen = client.currentScreen != null

        val now = System.nanoTime() / 1_000_000L
        val sw = ctx.scaledWindowWidth
        val sh = ctx.scaledWindowHeight
        val x = sw - TILE_W - MARGIN_RIGHT
        val totalH = snapshot.size * TILE_H + (snapshot.size - 1) * GAP
        var y = (sh - totalH) / 2

        val mouse = client.mouse
        val scale = client.window.scaleFactor
        val mx = (mouse.x / scale).toInt()
        val my = (mouse.y / scale).toInt()

        var hovered: HediffEntry? = null
        for (anim in snapshot) {
            val alpha = alphaFor(anim, now)
            // Intermittent attention shake: only while fully present (not mid-fade).
            val shake = if (anim.removedAtMs == 0L) shakeOffset(anim, now) else 0
            val dx = x + shake
            drawTile(ctx, client, anim.entry, dx, y, alpha)
            // Only hit-test while a screen frees the cursor; the locked
            // first-person crosshair would otherwise false-trigger on edge icons.
            if (screenOpen && mx in dx..(dx + TILE_W) && my in y..(y + TILE_H)) hovered = anim.entry
            y += TILE_H + GAP
        }

        val hov = hovered
        if (hov != null) drawTooltip(ctx, client, hov, mx, my)
    }

    /** 0..1 opacity from fade-in on appear and fade-out once [AnimEntry.removedAtMs] is set. */
    private fun alphaFor(anim: HediffHudState.AnimEntry, now: Long): Float {
        if (anim.removedAtMs != 0L) {
            val t = (now - anim.removedAtMs).toFloat() / HediffHudState.FADE_OUT_MS
            return (1f - t).coerceIn(0f, 1f)
        }
        val t = (now - anim.addedAtMs).toFloat() / HediffHudState.FADE_IN_MS
        return t.coerceIn(0f, 1f)
    }

    /**
     * Periodic horizontal wiggle to draw attention: a short damped sine burst
     * whose period scales with severity (severe hediffs wiggle more often),
     * staggered per-id so multiple icons don't shake in lockstep. Returns a pixel
     * offset (rounded).
     */
    private fun shakeOffset(anim: HediffHudState.AnimEntry, now: Long): Int {
        val period = HediffHudState.shakePeriodMs(anim.entry)
        // Stagger phase by id hash so icons shake at different moments.
        val phase = (anim.entry.id.hashCode().toLong() and 0xFFFF) % period
        val t = (now + phase) % period
        if (t >= HediffHudState.SHAKE_BURST_MS) return 0
        val p = t.toFloat() / HediffHudState.SHAKE_BURST_MS // 0..1 through the burst
        val damp = 1f - p // taper amplitude over the burst
        val wave = kotlin.math.sin(p * Math.PI.toFloat() * 6f) // ~3 oscillations
        return kotlin.math.round(wave * damp * HediffHudState.SHAKE_AMPLITUDE).toInt()
    }

    /**
     * Per-hediff PNG drawn at full color over a square severity-colored panel
     * framed in vanilla-button style (dark outline + light/shadow bevel), so the
     * colored icon art stays legible while the panel color conveys stage. Falls
     * back to unknown.png for ids that ship no texture. The full TEX_SIZE image is
     * scaled into the ICON box via the 1.21.1 scaling drawTexture overload.
     * [alpha] applies the fade-in/out opacity.
     */
    private fun drawTile(ctx: DrawContext, client: MinecraftClient, entry: HediffEntry, x: Int, y: Int, alpha: Float) {
        val preferred = textureFor(entry)
        val tex = if (client.resourceManager.getResource(preferred).isPresent) preferred else FALLBACK_TEX

        val x2 = x + TILE_W
        val y2 = y + TILE_H

        // Flat dark tile (DynamicMoodles style) + a 1px dark border. Alpha folds
        // in so the whole tile fades with the icon.
        ctx.fill(x, y, x2, y2, withAlpha(TILE_BG, alpha))
        drawBorder(ctx, x, y, x2, y2, withAlpha(TILE_BORDER, alpha))

        // Icon on the left, untinted so the source art keeps its own colors.
        val ix = x + PAD
        val iy = y + PAD
        ctx.setShaderColor(1f, 1f, 1f, alpha)
        ctx.drawTexture(tex, ix, iy, ICON, ICON, 0f, 0f, TEX_SIZE, TEX_SIZE, TEX_SIZE, TEX_SIZE)
        ctx.setShaderColor(1f, 1f, 1f, 1f)

        // Segmented vertical severity gauge on the right edge: fills bottom-up by
        // severity, tinted by stage (mild→extreme). Empty segments stay dark.
        drawGauge(ctx, x2 - GAUGE_W - 1, y + 1, y2 - 1, entry, alpha)
    }

    /** Bottom-up segmented gauge in the right column; filled count = severity. */
    private fun drawGauge(ctx: DrawContext, gx: Int, top: Int, bottom: Int, entry: HediffEntry, alpha: Float) {
        val filled = (entry.severity.coerceIn(0f, 1f) * SEGMENTS).let {
            // At least 1 segment for any present moodlet/hediff so it always reads.
            maxOf(1, kotlin.math.ceil(it.toDouble()).toInt()).coerceAtMost(SEGMENTS)
        }
        val onColor = withAlpha(tintFor(entry.stage), alpha)
        val offColor = withAlpha(SEG_OFF, alpha)
        val gx2 = gx + GAUGE_W
        val totalH = bottom - top
        val segH = totalH / SEGMENTS
        for (i in 0 until SEGMENTS) {
            // Segment 0 is the BOTTOM one; fill the lowest `filled` segments.
            val segBottom = bottom - i * segH
            val segTop = segBottom - segH + 1
            val color = if (i < filled) onColor else offColor
            ctx.fill(gx, segTop, gx2, segBottom, color)
        }
    }

    /** Scales an ARGB color's alpha channel by [alpha] (0..1). */
    private fun withAlpha(argb: Int, alpha: Float): Int {
        val a = (((argb ushr 24) and 0xFF) * alpha.coerceIn(0f, 1f)).toInt() and 0xFF
        return (a shl 24) or (argb and 0x00FFFFFF)
    }

    /** 1px-thick rectangular border (outline only) of [color]. */
    private fun drawBorder(ctx: DrawContext, x: Int, y: Int, x2: Int, y2: Int, color: Int) {
        ctx.fill(x, y, x2, y + 1, color)      // top
        ctx.fill(x, y2 - 1, x2, y2, color)    // bottom
        ctx.fill(x, y, x + 1, y2, color)      // left
        ctx.fill(x2 - 1, y, x2, y2, color)    // right
    }

    private fun drawTooltip(
        ctx: DrawContext,
        client: MinecraftClient,
        entry: HediffEntry,
        mx: Int,
        my: Int,
    ) {
        val tr = client.textRenderer
        // Label then description, each wrapped at a max width so long text doesn't
        // run off-screen. wrapLines splits at word boundaries into OrderedText.
        val lines = buildList {
            for (ot in tr.wrapLines(Text.literal(entry.label), TIP_MAX_WIDTH)) {
                add(ot to TIP_LABEL_COLOR)
            }
            if (entry.description.isNotBlank()) {
                for (ot in tr.wrapLines(Text.literal(entry.description), TIP_MAX_WIDTH)) {
                    add(ot to TIP_DESC_COLOR)
                }
            }
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
