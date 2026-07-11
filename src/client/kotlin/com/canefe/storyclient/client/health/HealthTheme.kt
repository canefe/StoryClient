package com.canefe.storyclient.client.health

import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiStyleVar

/**
 * Medieval / Minecraft-inventory theming for the [HealthPanel] window ONLY.
 *
 * ImGui has a single global style per context, and the Health window shares that
 * context with the DM panels ([com.canefe.storyclient.client.dm.DMPanelManager]).
 * So the theme is applied as a SCOPED push/pop around the Health window's
 * begin/end — the DM panels keep the default ImGui look.
 *
 * The wood frame is drawn as flat beveled rectangles (light top/left edge, dark
 * bottom/right edge) using colours sampled pixel-for-pixel from the Excalibur
 * resource pack's `gui/container/inventory.png`. Flat rects (rather than a
 * 9-sliced texture) match Minecraft's flat-shaded panel look, need no GL texture
 * upload, and can't smear the slot grid that's baked into the source texture's
 * interior.
 */
object HealthTheme {
    // Palette sampled from Excalibur inventory.png (see git history / handoff).
    // ImGui draw-list colours are packed ABGR (IM_COL32: a<<24|b<<16|g<<8|r).
    private const val BEVEL_LIGHT = 0xFF34_3B4FL.toInt()  // #4F3B34 top/left highlight
    private const val WOOD_BODY = 0xFF1F_2633L.toInt()    // #33261F panel fill
    private const val WOOD_SHADOW = 0xFF1B_1E28L.toInt()  // #281E1B bottom/right shadow
    private const val SLOT_RECESS = 0xFF18_1A23L.toInt()  // #231A18 recessed frame bg

    // rgba() helpers (0..1) mirroring the same sampled colours for style colours.
    private fun woodBody() = floatArrayOf(0x33 / 255f, 0x26 / 255f, 0x1F / 255f, 1f)
    private fun woodLight() = floatArrayOf(0x44 / 255f, 0x33 / 255f, 0x2E / 255f, 1f)
    private fun bevelLight() = floatArrayOf(0x4F / 255f, 0x3B / 255f, 0x34 / 255f, 1f)
    private fun slotRecess() = floatArrayOf(0x23 / 255f, 0x1A / 255f, 0x18 / 255f, 1f)
    private fun parchment() = floatArrayOf(0xE8 / 255f, 0xD8 / 255f, 0xB8 / 255f, 1f)

    /** Frame border thickness (px), matching the ~6px wood frame in the texture. */
    private const val FRAME = 6f

    /** Horizontal text margin inside the full-width content well (no wood inset). */
    private const val SIDE_MARGIN = 8f

    private var pushedColors = 0
    private var pushedVars = 0

    private fun col(idx: Int, c: FloatArray) {
        ImGui.pushStyleColor(idx, c[0], c[1], c[2], c[3])
        pushedColors++
    }

    private fun styleVar(idx: Int, x: Float, y: Float) {
        ImGui.pushStyleVar(idx, x, y)
        pushedVars++
    }

    private fun styleVar(idx: Int, v: Float) {
        ImGui.pushStyleVar(idx, v)
        pushedVars++
    }

    /**
     * Push the scoped medieval style. Call BEFORE [ImGui.begin] for the Health
     * window; pair with [pop] after [ImGui.end]. The transparent window bg lets
     * the hand-drawn wood frame (see [drawFrame]) show through.
     */
    fun push() {
        pushedColors = 0
        pushedVars = 0

        // Transparent bg: we paint the wood ourselves in drawFrame().
        col(ImGuiCol.WindowBg, floatArrayOf(0f, 0f, 0f, 0f))

        // Title bar in wood tones; parchment text.
        col(ImGuiCol.TitleBg, woodBody())
        col(ImGuiCol.TitleBgActive, woodLight())
        col(ImGuiCol.TitleBgCollapsed, woodBody())
        col(ImGuiCol.Text, parchment())
        col(ImGuiCol.TextDisabled, floatArrayOf(0x9A / 255f, 0x86 / 255f, 0x68 / 255f, 1f))

        // Borders/separators as light bevel wood.
        col(ImGuiCol.Border, bevelLight())
        col(ImGuiCol.Separator, bevelLight())

        // Progress-bar (severity) frame = recessed slot; the fill colour is set
        // per-row by HealthView via PlotHistogram, so we leave that alone.
        col(ImGuiCol.FrameBg, slotRecess())
        col(ImGuiCol.FrameBgHovered, slotRecess())
        col(ImGuiCol.FrameBgActive, slotRecess())

        // Buttons (Tend) as raised wood.
        col(ImGuiCol.Button, woodLight())
        col(ImGuiCol.ButtonHovered, bevelLight())
        col(ImGuiCol.ButtonActive, woodBody())

        // Tooltip / popup background stays opaque wood so text is readable.
        col(ImGuiCol.PopupBg, woodBody())

        // Flat, squared Minecraft look.
        styleVar(ImGuiStyleVar.WindowRounding, 0f)
        styleVar(ImGuiStyleVar.FrameRounding, 0f)
        styleVar(ImGuiStyleVar.WindowBorderSize, 0f)
        styleVar(ImGuiStyleVar.FrameBorderSize, 0f)
        // Taller title bar (imgui title bar height = fontSize + FramePadding.y*2).
        // Chunkier bar reads as more Minecraft-panel; also pads framed widgets.
        styleVar(ImGuiStyleVar.FramePadding, 6f, 8f)
        // Content well runs full width (matching the edge-to-edge title bar), so
        // horizontal window padding is a plain text margin, not a frame inset.
        // Vertical padding still clears the wood frame top/bottom.
        styleVar(ImGuiStyleVar.WindowPadding, SIDE_MARGIN, FRAME + 4f)
    }

    /** Pop everything [push] pushed. Call after [ImGui.end]. */
    fun pop() {
        if (pushedVars > 0) ImGui.popStyleVar(pushedVars)
        if (pushedColors > 0) ImGui.popStyleColor(pushedColors)
        pushedVars = 0
        pushedColors = 0
    }

    /**
     * Paint the beveled wood frame + fill for the CURRENT window, using its
     * background draw list so it sits behind the content. Call AFTER
     * [ImGui.begin] returns true, while the window is current.
     *
     * Bevel scheme matches Minecraft panels: light edge on top+left, dark edge
     * on bottom+right, flat wood body in the middle.
     */
    fun drawFrame() {
        val dl = ImGui.getWindowDrawList()
        val minX = ImGui.getWindowPosX()
        val minY = ImGui.getWindowPosY()
        val maxX = minX + ImGui.getWindowWidth()
        val maxY = minY + ImGui.getWindowHeight()

        // The recessed well starts at the ACTUAL bottom of the title bar, not a
        // guessed inset — so the wood title bar and the recessed body share one
        // clean seam at the same width instead of the bevel peeking over the top.
        // imgui title bar height = fontSize + FramePadding.y*2 = frameHeight.
        val titleBottom = minY + ImGui.getFrameHeight()

        // 1) Fill the whole window with the wood body (behind the title bar too).
        dl.addRectFilled(minX, minY, maxX, maxY, WOOD_BODY)

        // 2) Recessed inner well: full width, from the title bar bottom down to the
        //    bottom shadow. This is the only "content" region, edge-to-edge.
        dl.addRectFilled(minX, titleBottom, maxX, maxY - FRAME, SLOT_RECESS)

        // 3) Bottom shadow edge + a thin light bevel line at the title/body seam,
        //    so the recess reads as sunk below the title bar (both full width).
        dl.addRectFilled(minX, maxY - FRAME, maxX, maxY, WOOD_SHADOW)          // bottom
        dl.addRectFilled(minX, titleBottom, maxX, titleBottom + 1f, BEVEL_LIGHT) // seam
    }
}
