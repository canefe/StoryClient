package com.canefe.storyclient.client.skills

import net.minecraft.client.MinecraftClient
import net.minecraft.util.Identifier

/**
 * Resolves a skill id to a GL texture handle for ImGui, mirroring
 * [com.canefe.storyclient.client.health.PaperDoll]'s `glId` — the SAME proven
 * `textureManager.bindTexture` + `getTexture().glId` path the Health tab uses.
 *
 * Reuses EXISTING vanilla item textures (in the `minecraft` namespace), so it
 * picks up whatever art is live — the Excalibur resource pack's item textures
 * when the pack is applied, vanilla otherwise — with no new assets. Unmapped
 * skills fall back to a generic book icon.
 */
object SkillIcons {
    /** skill id -> vanilla item texture path (under assets/minecraft/textures/). */
    private val byId = mapOf(
        "combat" to "item/iron_sword",
        "shooting" to "item/bow",
        "runesmithing" to "item/enchanted_book",
        "cooking" to "item/cooked_beef",
        "mining" to "item/iron_pickaxe",
        "bartering" to "item/emerald",
        "persuasion" to "item/written_book",
    )

    /**
     * The vanilla item texture path (under assets/minecraft/textures/) for a
     * skill id, e.g. "item/iron_sword". Falls back to a generic book. Used by
     * the native (DrawContext) Skills panel, which draws via [Identifier] +
     * drawTexture rather than the GL-handle [glId] path imgui needs.
     */
    fun pathFor(skillId: String): String = byId[skillId] ?: "item/book"

    fun glId(skillId: String): Long {
        val path = byId[skillId] ?: "item/book"
        val id = Identifier.of("minecraft", "textures/$path.png")
        return runCatching {
            val tm = MinecraftClient.getInstance().textureManager
            tm.bindTexture(id) // force upload to GL on first use
            tm.getTexture(id).glId.toLong()
        }.getOrDefault(0L)
    }
}
