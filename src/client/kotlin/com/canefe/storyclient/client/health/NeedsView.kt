package com.canefe.storyclient.client.health

import imgui.ImGui
import imgui.flag.ImGuiCol

/**
 * Renders a character's needs (hunger, thirst, …) as labeled bars for the DM
 * panel. Values are 0..100 where HIGH = satisfied, so the color runs red (empty,
 * starving) → green (full) — the inverse of [HealthView]'s severity coloring.
 *
 * Known needs are ordered/prettified; any extra need ids the sim sends are shown
 * after, alphabetically, so the panel never silently drops data.
 */
object NeedsView {

    /** Display order + labels for the needs we expect; others fall through. */
    private val KNOWN = linkedMapOf(
        "hunger" to "Hunger",
        "thirst" to "Thirst",
        "rest" to "Rest",
        "recreation" to "Recreation",
    )

    /** red (0 = empty/urgent) → yellow (.5) → green (1 = full) RGB. */
    private fun needColor(frac: Float): FloatArray {
        val c = frac.coerceIn(0f, 1f)
        val r = (2f - c * 2f).coerceAtMost(1f)
        val g = (c * 2f).coerceAtMost(1f)
        return floatArrayOf(r, g, 0.15f)
    }

    private fun needBar(label: String, value: Float) {
        val frac = (value / 100f).coerceIn(0f, 1f)
        ImGui.text(label)
        val col = needColor(frac)
        ImGui.pushStyleColor(ImGuiCol.PlotHistogram, col[0], col[1], col[2], 1f)
        ImGui.progressBar(frac, -1f, 14f, "${value.toInt()}")
        ImGui.popStyleColor()
    }

    private fun prettyId(id: String): String =
        id.split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    /** Render a "Needs" section for [needs] (id -> 0..100). No-op if empty. */
    fun render(needs: Map<String, Float>) {
        if (needs.isEmpty()) {
            ImGui.textDisabled("No needs data.")
            return
        }
        for ((id, label) in KNOWN) {
            val v = needs[id] ?: continue
            needBar(label, v)
        }
        // Surface any unexpected needs the sim sends rather than dropping them.
        needs.keys
            .filter { it !in KNOWN }
            .sorted()
            .forEach { needBar(prettyId(it), needs.getValue(it)) }
    }
}
