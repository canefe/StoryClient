package com.canefe.storyclient.client.panel

import com.canefe.storyclient.client.health.HealthNativeView
import com.canefe.storyclient.client.skills.SkillsNativeView
import com.canefe.storyclient.client.skills.SkillsState
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.util.Identifier

/**
 * Single wood-framed panel that lives to the LEFT of the inventory and hosts the
 * player-facing tabs (Health, Skills, …) as clickable ICON TABS in its title
 * bar. Replaces the two separate imgui-era windows: one frame, one title bar,
 * body swapped by the active tab.
 *
 * Extensible: add a [Tab] to [tabs] with an icon + a body renderer and it shows
 * up automatically. The active tab defaults to Health on first open and is then
 * remembered for the session ([active]).
 *
 * Interaction: [render] records tab-icon hit-regions; body interaction (Health's
 * Tend buttons) is delegated to the active view. [clickAt] handles both — tab
 * switches first, then the body.
 */
object StoryTabsPanel {
    // --- shared frame layout (px) ---
    const val WIDTH = 200
    private const val FRAME = 4
    private const val TITLE_H = 18       // taller bar to seat 16px icon tabs
    private const val PAD_TOP = 6
    private const val ICON = 16
    private const val TAB_GAP = 2
    private const val TABS_RIGHT_PAD = 6

    // --- palette (ARGB), shared with the native views ---
    private const val WOOD_BODY = 0xFF33261F.toInt()
    private const val WOOD_LIGHT = 0xFF44332E.toInt()
    private const val WOOD_SHADOW = 0xFF281E1B.toInt()
    private const val WELL = 0xFF231A18.toInt()
    private const val SEAM = 0xFF4F3B34.toInt()
    private const val PARCHMENT = 0xFFE8D8B8.toInt()
    private const val TAB_ACTIVE_BG = 0xFF5A463D.toInt()
    private const val TAB_HOVER_BG = 0xFF44332E.toInt()
    private const val TAB_BORDER = 0xFF4F3B34.toInt()

    /** A tab: its title, title-bar icon texture, and how to render/measure its body. */
    class Tab(
        val id: String,
        val title: String,
        val icon: Identifier,
        /** Draw the body into the well at (x,y) width [w]; mouseX/Y for hover. */
        val renderBody: (ctx: DrawContext, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int) -> Unit,
        /** Pixel height the body needs, for sizing the frame. */
        val contentHeight: () -> Int,
        /** Route a click inside the body; return true if consumed. */
        val clickBody: (mouseX: Int, mouseY: Int) -> Boolean = { _, _ -> false },
    )

    private val tabs: List<Tab> = listOf(
        Tab(
            id = "health",
            title = "Health",
            icon = Identifier.of("minecraft", "textures/item/apple.png"),
            renderBody = { ctx, x, y, w, mx, my -> HealthNativeView.renderBody(ctx, x, y, w, mx, my) },
            contentHeight = { HealthNativeView.contentHeight() },
            clickBody = { mx, my -> HealthNativeView.clickAt(mx, my) },
        ),
        Tab(
            id = "skills",
            title = "Skills",
            icon = Identifier.of("minecraft", "textures/item/book.png"),
            renderBody = { ctx, x, y, w, _, _ -> SkillsNativeView.renderBody(ctx, x, y, w, SkillsState.active) },
            contentHeight = { SkillsNativeView.contentHeight(SkillsState.active) },
        ),
    )

    /** Active tab id; defaults to Health, remembered across opens this session. */
    @Volatile
    var active: String = tabs.first().id
        private set

    /** Tab-icon hit-regions recorded during [render], consumed by [clickAt]. */
    private data class TabHit(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val id: String)
    private val tabHits = ArrayList<TabHit>()

    fun select(id: String) {
        if (tabs.any { it.id == id }) active = id
    }

    private fun activeTab(): Tab = tabs.firstOrNull { it.id == active } ?: tabs.first()

    /** Draw the framed, tabbed panel with its top-left at ([x],[y]). */
    fun render(ctx: DrawContext, x: Int, y: Int, mouseX: Int, mouseY: Int) {
        tabHits.clear()
        val client = MinecraftClient.getInstance()
        val tr = client.textRenderer
        val tab = activeTab()

        val wellTop = y + TITLE_H
        val bodyX = x + 8
        val bodyY = wellTop + PAD_TOP
        val bodyW = WIDTH - 16
        val h = TITLE_H + PAD_TOP + tab.contentHeight() + FRAME
        val x2 = x + WIDTH
        val y2 = y + h

        // --- wood frame ---
        ctx.fill(x, y, x2, y2, WOOD_BODY)
        ctx.fill(x, y, x2, y + 1, WOOD_LIGHT)
        ctx.fill(x, y2 - FRAME, x2, y2, WOOD_SHADOW)
        ctx.fill(x, wellTop, x2, y2 - FRAME, WELL)
        ctx.fill(x, wellTop, x2, wellTop + 1, SEAM)

        // --- title: active tab name on the left ---
        ctx.drawText(tr, tab.title, x + 8, y + (TITLE_H - 8) / 2, PARCHMENT, false)

        // --- icon tabs on the right of the title bar ---
        val iconY = y + (TITLE_H - ICON) / 2
        var ix = x2 - TABS_RIGHT_PAD - tabs.size * (ICON + TAB_GAP) + TAB_GAP
        for (t in tabs) {
            val bx0 = ix - 1
            val by0 = iconY - 1
            val bx1 = ix + ICON + 1
            val by1 = iconY + ICON + 1
            val hovered = mouseX in bx0..bx1 && mouseY in by0..by1
            when {
                t.id == active -> {
                    ctx.fill(bx0, by0, bx1, by1, TAB_ACTIVE_BG)
                    drawBorder(ctx, bx0, by0, bx1, by1, TAB_BORDER)
                }
                hovered -> ctx.fill(bx0, by0, bx1, by1, TAB_HOVER_BG)
            }
            // Dim inactive icons slightly so the active one reads as selected.
            if (t.id != active) ctx.setShaderColor(1f, 1f, 1f, 0.55f)
            ctx.drawTexture(t.icon, ix, iconY, ICON, ICON, 0f, 0f, ICON, ICON, ICON, ICON)
            if (t.id != active) ctx.setShaderColor(1f, 1f, 1f, 1f)

            tabHits.add(TabHit(bx0, by0, bx1, by1, t.id))
            ix += ICON + TAB_GAP
        }

        // --- active tab body ---
        tab.renderBody(ctx, bodyX, bodyY, bodyW, mouseX, mouseY)
    }

    private fun drawBorder(ctx: DrawContext, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        ctx.fill(x0, y0, x1, y0 + 1, color)
        ctx.fill(x0, y1 - 1, x1, y1, color)
        ctx.fill(x0, y0, x0 + 1, y1, color)
        ctx.fill(x1 - 1, y0, x1, y1, color)
    }

    /**
     * Handle a click at ([mouseX],[mouseY]). Tab-icon clicks switch the active
     * tab; otherwise the click is routed to the active tab's body. Returns true
     * if consumed.
     */
    fun clickAt(mouseX: Int, mouseY: Int): Boolean {
        val hit = tabHits.firstOrNull { mouseX in it.x0..it.x1 && mouseY in it.y0..it.y1 }
        if (hit != null) {
            active = hit.id
            return true
        }
        return activeTab().clickBody(mouseX, mouseY)
    }
}
