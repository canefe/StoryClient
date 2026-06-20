package com.canefe.storyclient.client.health

import net.minecraft.client.MinecraftClient
import net.minecraft.registry.Registries

/**
 * Best-effort local check for whether the player holds a Story medicine and
 * which tier is best. Server re-validates the actual inventory on receive;
 * this only gates the "Tend" button UX so the player gets visual feedback.
 *
 * Detection: map each ItemStack's vanilla registry id to a sim medicine id
 * using the fixed Material→sim-id mapping from items.yml (Task 1). No PDC
 * or custom_data reads needed — the four medicine items each use a distinct
 * vanilla Material, so the registry id is a reliable best-effort signal.
 */
object MedicineInventory {

    /** Fixed map: vanilla item registry id → Story sim medicine id (from items.yml). */
    private val REGISTRY_TO_SIM = mapOf(
        "minecraft:paper"             to "bandage",
        "minecraft:kelp"              to "herbal_medicine",
        "minecraft:ghast_tear"        to "medicine",
        "minecraft:phantom_membrane"  to "glitterworld_medicine",
    )

    /** Highest tier first — auto-picks the best medicine the player holds. */
    private val TIERS = listOf(
        "glitterworld_medicine",
        "medicine",
        "herbal_medicine",
        "bandage",
    )

    /**
     * Returns the best-tier medicine sim-id the player currently holds, or
     * null if none detected. Iterates the full player inventory (hotbar +
     * main inventory + offhand).
     */
    fun bestHeld(): String? {
        val player = MinecraftClient.getInstance().player ?: return null
        val held = HashSet<String>()
        for (i in 0 until player.inventory.size()) {
            val stack = player.inventory.getStack(i)
            if (stack.isEmpty) continue
            val regId = Registries.ITEM.getId(stack.item).toString()
            REGISTRY_TO_SIM[regId]?.let { held.add(it) }
        }
        return TIERS.firstOrNull { it in held }
    }
}
