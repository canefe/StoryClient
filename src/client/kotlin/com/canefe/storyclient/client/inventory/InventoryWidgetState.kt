package com.canefe.storyclient.client.inventory

/**
 * Current content of the inventory corner widget (the panel drawn in the
 * freed-up crafting-grid corner of the survival inventory). Fully
 * content-agnostic: StoryMC pushes a list of [WidgetRow]s and the client renders
 * them in order. Replaced wholesale on each "story:inv_widget" packet.
 *
 * Read each frame by the renderer in InventoryCraftingHiderMixin; written by
 * [InventoryWidgetPacketReceiver].
 */
object InventoryWidgetState {

    /**
     * One display row. [icon] is an optional texture/item id ("" = none),
     * [value] is pre-formatted by the server (no client-side number formatting),
     * and [bar] is an optional 0..1 meter fill (null = no bar).
     */
    data class WidgetRow(
        val icon: String,
        val label: String,
        val value: String,
        val bar: Float?,
    )

    @Volatile
    var rows: List<WidgetRow> = emptyList()
        private set

    fun set(rows: List<WidgetRow>) {
        this.rows = rows
    }

    fun clear() {
        this.rows = emptyList()
    }
}
