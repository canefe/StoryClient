package com.canefe.storyclient.client.health

import imgui.ImGui
import net.minecraft.client.MinecraftClient
import net.minecraft.util.Identifier

/**
 * Internal-organ strip shown under the paper-doll. Organs live inside the torso
 * and can't be drawn on the outer silhouette, so the sim's internal body parts
 * (heart/lung/brain/…) get their own row of icons, tinted red when injured.
 * Uses the "Nice Health Tab" mod's organs/ art. Each entry maps the sim wire
 * part id(s) to an icon; paired organs (lungs/kidneys) share one icon and tint
 * if either side is hurt.
 */
object OrganRow {
    private const val BASE = "textures/health/organs"

    /** display icon size; aspect derived from the native art. */
    private data class Organ(val file: String, val aspect: Float, val wireParts: Set<String>)

    private val organs = listOf(
        Organ("brain", 128f / 64f, setOf("brain")),
        Organ("heart", 1f, setOf("heart")),
        Organ("lung", 128f / 256f, setOf("left_lung", "right_lung")),
        Organ("liver", 128f / 64f, setOf("liver")),
        Organ("kidney", 64f / 128f, setOf("left_kidney", "right_kidney")),
        Organ("stomach", 1f, setOf("stomach", "intestines")),
    )

    private fun glId(file: String): Long {
        val id = Identifier.of("storyclient", "$BASE/$file.png")
        val tm = MinecraftClient.getInstance().textureManager
        tm.bindTexture(id)
        return tm.getTexture(id).glId.toLong()
    }

    /** Draw the organ icons at the current cursor, tinting any injured organ. */
    fun render(injuredWireParts: Set<String>, iconH: Float = 26f) {
        var first = true
        for (o in organs) {
            val tid = glId(o.file)
            if (tid <= 0L) continue
            if (!first) ImGui.sameLine()
            first = false

            val h = iconH
            val w = h * o.aspect
            val injured = o.wireParts.any { it in injuredWireParts }
            if (injured) {
                ImGui.image(tid, w, h, 0f, 0f, 1f, 1f, 1f, 0.35f, 0.35f, 1f)
            } else {
                // Dim healthy organs so injured ones pop.
                ImGui.image(tid, w, h, 0f, 0f, 1f, 1f, 0.55f, 0.55f, 0.55f, 1f)
            }
        }
    }
}
