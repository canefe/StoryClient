package com.canefe.storyclient.client.screen

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.math.MathHelper

/**
 * Skyrim-style loading screen shared by the two black load phases (server connect
 * via `ConnectScreenMixin` and terrain download via [BlackFadeTerrainScreen]).
 *
 * A randomly-chosen full-bleed art image is painted over solid black with a slow
 * Ken Burns drift (zoom + pan), and a random italic flavor tip sits in the
 * bottom-right corner. Because both phases delegate here and [begin] is idempotent
 * for the duration of a single load, the screen stays *continuous* across
 * connect → terrain with no reshuffle or black flash between them.
 *
 * On handoff the same [render] path is reused by [ScreenFadeOverlay] (with a
 * ramping `alpha`) so the chosen image — not a black rect — fades away over the
 * live world, then the spawn cinematic plays. [reset] clears state so the next
 * join reshuffles.
 */
object LoadingScreenRenderer {

    /** Bundled art. Drop PNGs under `assets/storyclient/textures/loading/` and list them here. */
    private val IMAGES: List<Identifier> = listOf(
        Identifier.of("storyclient", "textures/loading/worldtree.png"),
    )

    /** Native pixel size of each loading image (all art is authored at this size). */
    private const val TEX_W = 1280
    private const val TEX_H = 832

    /** Random flavor tips, Skyrim-style. */
    private val TIPS: List<String> = listOf(
        "Some tales tell of ancient heroes whose Voices were powerful enough to tame and ride dragons.",
        "The world remembers what you do. NPCs speak of your deeds long after you have moved on.",
        "Hunger and thirst are real. Keep your pack stocked before venturing far from a hold.",
        "A blade is only as sharp as the hand that wields it — and the timing behind the swing.",
        "Listen before you speak. Some doors open only to those who have earned a name.",
        "Wounds left untended fester. Seek a healer, or learn to bind them yourself.",
        "Every soul you meet is living their own story. You are only one thread in the weave.",
    )

    /** Slow drift parameters. */
    private const val DRIFT_MS = 20_000.0f      // full cycle length
    private const val ZOOM_FROM = 1.06f         // start slightly zoomed
    private const val ZOOM_TO = 1.14f           // end more zoomed
    /**
     * Pan amplitude as a small fraction of the screen width — a *fixed* subtle drift,
     * NOT proportional to the crop overflow. Keeping it small ensures the image's
     * centered subject stays on-screen at any window aspect; the position is then
     * clamped to the overflow so this pan can still never expose black.
     */
    private const val PAN_FRAC = 0.025f

    @Volatile private var chosenImage: Identifier? = null
    @Volatile private var chosenTip: String = ""
    @Volatile private var startMs: Long = 0L

    /**
     * Begins a load sequence: picks a random image + tip and records the animation
     * start. Idempotent while a sequence is active — repeated calls (connect tick,
     * terrain swap) do NOT reshuffle, keeping the screen continuous. Call [reset]
     * to end the sequence.
     */
    fun begin() {
        if (chosenImage != null) return
        val image = IMAGES.randomOrNull() ?: return
        chosenImage = image
        chosenTip = TIPS.random()
        startMs = System.currentTimeMillis()
    }

    /** Clears state so the next [begin] reshuffles. Called once the reveal fade completes. */
    fun reset() {
        chosenImage = null
        chosenTip = ""
        startMs = 0L
    }

    /** True once [begin] has chosen art — lets [ScreenFadeOverlay] know to fade the image, not black. */
    fun isActive(): Boolean = chosenImage != null

    /**
     * Paints one full loading frame: black, then the drifting image, then the tip.
     * [alpha] (0..1) scales the whole frame so the reveal fade can ramp it to zero.
     */
    fun render(ctx: DrawContext, width: Int, height: Int, alpha: Float) {
        begin()
        val image = chosenImage ?: run {
            // No art available — fall back to plain black so we never regress to nothing.
            ctx.fill(0, 0, width, height, (clampByte(alpha) shl 24))
            return
        }

        val a = alpha.coerceIn(0f, 1f)

        // Black backdrop (Skyrim-on-black: art bleeds over solid black).
        ctx.fill(0, 0, width, height, (clampByte(a) shl 24))

        drawDriftingImage(ctx, image, width, height, a)
        drawTip(ctx, width, height, a)
    }

    /**
     * Draws the image scaled to COVER the screen (cropping the overflowing axis) at
     * ANY window size/aspect, with a slow zoom + horizontal pan over [DRIFT_MS].
     *
     * COVER (`max` of the two axis scales) guarantees both `drawW ≥ width` and
     * `drawH ≥ height`, so the frame is always fully covered and the centered
     * subject stays visible. The pan is a *small fixed* fraction of the width (not
     * proportional to the overflow — that would sweep the subject off-screen on
     * narrow windows), and the origin is clamped to the overflow so the pan can
     * still never expose black. Dimensions round UP so truncation leaves no seam.
     */
    private fun drawDriftingImage(ctx: DrawContext, image: Identifier, width: Int, height: Int, alpha: Float) {
        val elapsed = (System.currentTimeMillis() - startMs).toFloat()
        // Ping-pong 0→1→0 so the drift eases and reverses instead of snapping.
        val phase = (elapsed % (DRIFT_MS * 2f)) / DRIFT_MS
        val t = if (phase <= 1f) phase else 2f - phase
        val ease = t * t * (3f - 2f * t) // smoothstep

        val zoom = MathHelper.lerp(ease, ZOOM_FROM, ZOOM_TO)

        // COVER scale: the larger of the two axis ratios fills both axes.
        val coverScale = maxOf(width.toFloat() / TEX_W, height.toFloat() / TEX_H)
        val scale = coverScale * zoom

        // Round the drawn size UP so truncation can't leave a sub-pixel black seam.
        val drawW = kotlin.math.ceil(TEX_W * scale).toInt()
        val drawH = kotlin.math.ceil(TEX_H * scale).toInt()

        // Small fixed pan (fraction of width), independent of how much we cropped —
        // so the centered subject stays visible even when the window is narrow.
        val panX = MathHelper.lerp(ease, -PAN_FRAC, PAN_FRAC) * width

        // Clamp the origin so the image always fully covers [0,width]/[0,height];
        // the clamp is what guarantees the pan never exposes black at an edge.
        val x = MathHelper.clamp((width - drawW) / 2f + panX, (width - drawW).toFloat(), 0f)
        val y = MathHelper.clamp((height - drawH) / 2f, (height - drawH).toFloat(), 0f)

        ctx.setShaderColor(1f, 1f, 1f, alpha)
        // 1.21.1 scaling overload: dest x/y/w/h, src u/v, region w/h, tex w/h.
        ctx.drawTexture(
            image,
            x.toInt(), y.toInt(),
            drawW, drawH,
            0f, 0f,
            TEX_W, TEX_H,
            TEX_W, TEX_H,
        )
        ctx.setShaderColor(1f, 1f, 1f, 1f)
    }

    /** Bottom-right italic flavor tip, word-wrapped to the lower-right quadrant. */
    private fun drawTip(ctx: DrawContext, width: Int, height: Int, alpha: Float) {
        if (chosenTip.isEmpty()) return
        val client = MinecraftClient.getInstance()
        val tr = client.textRenderer

        val margin = 24
        val maxWidth = (width * 0.45f).toInt().coerceAtLeast(120)
        val italic = Text.literal(chosenTip).formatted(net.minecraft.util.Formatting.ITALIC)
        val lines = tr.wrapLines(italic, maxWidth)

        val lineH = tr.fontHeight + 2
        var y = height - margin - lines.size * lineH
        val color = (clampByte(alpha) shl 24) or 0x00FFFFFF // white, faded

        for (line in lines) {
            val w = tr.getWidth(line)
            val x = width - margin - w
            ctx.drawText(tr, line, x, y, color, true)
            y += lineH
        }
    }

    private fun clampByte(alpha: Float): Int = (alpha.coerceIn(0f, 1f) * 255f).toInt() and 0xFF
}
