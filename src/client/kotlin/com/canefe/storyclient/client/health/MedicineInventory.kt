package com.canefe.storyclient.client.health

import net.minecraft.client.MinecraftClient
import net.minecraft.item.ItemStack
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
    fun bestHeld(): String? = resolveTend().simId

    /** The sim medicine id an [ItemStack] maps to, or null if it isn't medicine. */
    private fun simIdOf(stack: ItemStack): String? {
        if (stack.isEmpty) return null
        return REGISTRY_TO_SIM[Registries.ITEM.getId(stack.item).toString()]
    }

    /**
     * The medicine that a Tend would use right now, and the item to show in the
     * wound's medicine slot. Resolution order:
     *  1. the stack on the player's CURSOR, if it's a medicine (lets the player
     *     pick a specific tier by holding it),
     *  2. otherwise the best-tier medicine held anywhere in the inventory,
     *  3. otherwise none → a bare-handed tend ([simId] == null, [stack] empty).
     */
    data class TendChoice(val simId: String?, val stack: ItemStack)

    fun resolveTend(): TendChoice {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return TendChoice(null, ItemStack.EMPTY)

        // 1. cursor item, if it's medicine.
        val cursor = client.player?.currentScreenHandler?.cursorStack ?: ItemStack.EMPTY
        simIdOf(cursor)?.let { return TendChoice(it, cursor) }

        // 2. best-tier medicine held anywhere. Track the representative stack.
        val byTier = HashMap<String, ItemStack>()
        for (i in 0 until player.inventory.size()) {
            val stack = player.inventory.getStack(i)
            val sim = simIdOf(stack) ?: continue
            byTier.putIfAbsent(sim, stack)
        }
        val best = TIERS.firstOrNull { it in byTier }
        return if (best != null) TendChoice(best, byTier.getValue(best)) else TendChoice(null, ItemStack.EMPTY)
    }
}
