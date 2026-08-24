package com.canefe.storyclient.client.confrontation

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext

/**
 * Choices-only overlay for a LOCKED confrontation. Deliberately NO full-screen
 * dim/panel — it draws only the prompt, the numbered option rows, and (when
 * allowed) the free-text line, low over the live cinematic view. Text uses an
 * outlined draw so it stays legible against the 3D scene.
 *
 * Rendered from HudRenderCallback while [ConfrontationState.active].
 */
object ConfrontationOverlay {

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val SHADOW = 0xFF101010.toInt()
    private const val DIM = 0xFFB0B0B0.toInt()

    /** Current free-text buffer (mirrors DecisionHud's freeform buffer). */
    var freeformInput: String = ""
        private set

    var freeformMode: Boolean = false
        private set

    /** Text scale for the whole overlay (bigger = larger UI). */
    private const val SCALE = 1.6f

    private const val LINE_H = 12 // unscaled line height
    private const val PAD = 8 // panel padding (unscaled units, pre-scale)
    private const val PANEL_BG = 0xCC101014.toInt() // near-opaque dark panel
    private const val PANEL_EDGE = 0xFF3A3A44.toInt()

    /**
     * The mechanics suffix shown after a choice label: which skill is rolled,
     * the player's own value in it, and what they're beating. Empty for an
     * unchecked choice (nothing is rolled). Placeholder text-tag form; a richer
     * visual treatment can replace this later without touching the wire/state.
     *   static:  " [Persuasion 0 vs DC 14]"
     *   opposed: " [Combat 5 vs their Willpower 0]"
     */
    private fun mechanicsTag(check: CheckView?): String {
        if (check == null) return ""
        val skill = check.skill.replaceFirstChar { it.uppercase() }
        return when (check.kind) {
            "static" ->
                if (check.dc != null) " [$skill ${check.actorSkillValue} vs DC ${check.dc}]" else ""
            "opposed" -> {
                val vs = (check.vsSkill ?: "").replaceFirstChar { it.uppercase() }
                " [$skill ${check.actorSkillValue} vs their $vs ${check.targetSkillValue ?: 0}]"
            }
            else -> ""
        }
    }

    fun render(ctx: DrawContext) {
        if (!ConfrontationState.active) return
        val mc = MinecraftClient.getInstance()
        val font = mc.textRenderer
        val sw = ctx.scaledWindowWidth
        val sh = ctx.scaledWindowHeight

        // Max text width (in UNSCALED font units) before wrapping. Keep the whole
        // scaled panel inside the screen with a right margin.
        val marginX = 12f
        val rightMargin = 12f
        val maxTextW = ((sw - marginX - rightMargin) / SCALE - PAD * 2).toInt().coerceAtLeast(40)

        // Build wrapped lines. Each source string wraps to maxTextW; continuation
        // lines of a choice are indented so the [n] label stays readable.
        data class Line(val text: String, val color: Int)
        val lines = ArrayList<Line>()
        fun addWrapped(text: String, color: Int, hangingIndent: String = "") {
            val wrapped = font.textHandler.wrapLines(text, maxTextW, net.minecraft.text.Style.EMPTY)
            wrapped.forEachIndexed { i, seg ->
                val s = if (i == 0) seg.string else hangingIndent + seg.string
                lines.add(Line(s, color))
            }
        }
        ConfrontationState.prompt?.let { addWrapped(it, WHITE) }
        if (ConfrontationState.myTurn) {
            ConfrontationState.choices.forEachIndexed { i, choice ->
                addWrapped("[${i + 1}] ${choice.label}${mechanicsTag(choice.check)}", WHITE, hangingIndent = "     ")
            }
            if (ConfrontationState.allowFreeText) {
                val text = if (freeformMode) "> $freeformInput|" else "[T] say something…"
                addWrapped(text, DIM)
            }
        } else {
            lines.add(Line("…waiting…", DIM))
        }
        if (lines.isEmpty()) return

        // Panel geometry in SCALED screen space. Anchored bottom-left.
        val contentW = (lines.maxOf { font.getWidth(it.text) }).toFloat()
        val contentH = (lines.size * LINE_H).toFloat()
        val panelW = (contentW + PAD * 2) * SCALE
        val panelH = (contentH + PAD * 2) * SCALE
        val marginY = 12f
        val px = marginX
        val py = sh - marginY - panelH

        // Backdrop panel (drawn in screen space, then text scaled on top). This
        // fixes the z/legibility problem: opaque panel behind the text.
        ctx.fill(px.toInt(), py.toInt(), (px + panelW).toInt(), (py + panelH).toInt(), PANEL_BG)
        // 1px edge
        ctx.fill(px.toInt(), py.toInt(), (px + panelW).toInt(), (py + 1).toInt(), PANEL_EDGE)
        ctx.fill(px.toInt(), (py + panelH - 1).toInt(), (px + panelW).toInt(), (py + panelH).toInt(), PANEL_EDGE)

        // Scaled text.
        val m = ctx.matrices
        m.push()
        m.translate((px + PAD * SCALE).toDouble(), (py + PAD * SCALE).toDouble(), 0.0)
        m.scale(SCALE, SCALE, 1.0f)
        var ty = 0
        for (ln in lines) {
            ctx.drawText(font, ln.text, 1, ty + 1, SHADOW, false)
            ctx.drawText(font, ln.text, 0, ty, ln.color, false)
            ty += LINE_H
        }
        m.pop()
    }

    // ── free-text buffer (driven by ConfrontationFreeformScreen) ─────────────

    fun openFreeform() {
        if (!ConfrontationState.allowFreeText) return
        freeformMode = true
        freeformInput = ""
    }

    fun appendFreeformChar(c: Char) {
        if (freeformMode) freeformInput += c
    }

    fun backspaceFreeform() {
        if (freeformMode && freeformInput.isNotEmpty()) {
            freeformInput = freeformInput.dropLast(1)
        }
    }

    fun cancelFreeform() {
        freeformMode = false
        freeformInput = ""
    }

    fun submitFreeform() {
        val id = ConfrontationState.confrontationId
        val text = freeformInput.trim()
        freeformMode = false
        freeformInput = ""
        if (id != null && text.isNotEmpty()) {
            ConfrontationPacketReceiver.sendFreeText(id, text)
            ConfrontationState.clearMyTurn()
        }
    }

    /** Pick option [index] (0-based) if it's our turn and it exists. */
    fun pick(index: Int) {
        println("[Confrontation] pick($index) myTurn=${ConfrontationState.myTurn} choices=${ConfrontationState.choices.size} conf=${ConfrontationState.confrontationId}")
        if (!ConfrontationState.myTurn) {
            println("[Confrontation] pick ignored: not my turn")
            return
        }
        val choice = ConfrontationState.choices.getOrNull(index) ?: run {
            println("[Confrontation] pick ignored: no choice at index $index")
            return
        }
        val id = ConfrontationState.confrontationId ?: run {
            println("[Confrontation] pick ignored: no confrontationId")
            return
        }
        println("[Confrontation] sending pick choiceId=${choice.id} conf=$id")
        ConfrontationPacketReceiver.sendPick(id, choice.id, choice.label)
        ConfrontationState.clearMyTurn()
    }
}
