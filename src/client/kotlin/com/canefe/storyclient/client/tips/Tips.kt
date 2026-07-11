package com.canefe.storyclient.client.tips

/**
 * The registry of all one-time tips, keyed by [Tip.id]. This is the single place
 * tips are authored: add a [Tip] here, then call `TipManager.show(id)` from the
 * relevant trigger point. Ids are stable strings — they are what gets persisted
 * in the seen-set, so don't rename an id without accepting that players will see
 * that tip again.
 */
object Tips {

    val ALL: List<Tip> = listOf(
        Tip(
            id = "ooc_camera",
            title = "Out of Character",
            subtitle = "You've stepped out of your body. Move the mouse to orbit, " +
                "scroll to zoom, press O to return.",
            style = TipStyle.POPUP,
        ),
        // TOAST (top-left), not POPUP: the inventory Screen occludes the centered
        // popup (ImmersiveMessages draws on the HUD layer, under any open Screen),
        // but the top-left corner is clear of the centered inventory GUI so the
        // toast stays visible while the inventory is open.
        Tip(
            id = "inventory_health_tab",
            title = "Health Tab",
            subtitle = "The panel beside your inventory shows your Health — wounds, " +
                "conditions, and bleeding. Click the Health icon to view it.",
            style = TipStyle.TOAST,
        ),
        Tip(
            id = "inventory_skills_tab",
            title = "Skills Tab",
            subtitle = "Switch to the Skills tab in that same panel to track your " +
                "skills and progress.",
            style = TipStyle.TOAST,
        ),
    )

    private val byId: Map<String, Tip> = ALL.associateBy { it.id }

    operator fun get(id: String): Tip? = byId[id]
}
