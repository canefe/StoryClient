package com.canefe.storyclient.client.health

import imgui.ImGui
import net.minecraft.client.MinecraftClient
import net.minecraft.util.Identifier

object PaperDoll {
    private const val BASE = "textures/health/baseliner/male"
    // (part id, file) in back→front draw order.
    private val layers = listOf(
        "outline" to "outline",
        "torso" to "torso",
        "leg" to "leg",
        "upperarm" to "upperarm",
        "lowerarm" to "lowerarm",
        "hand" to "hand",
        "neck" to "neck",
        "head" to "head",
        "feet" to "feet",
    )

    // ImGui.image takes long; AbstractTexture.getGlId() returns int — widen to long.
    private fun glId(file: String): Long {
        val id = Identifier.of("storyclient", "$BASE/$file.png")
        val tm = MinecraftClient.getInstance().textureManager
        // Force load on first use; bindTexture ensures the texture is uploaded to GL.
        tm.bindTexture(id)
        return tm.getTexture(id).glId.toLong()
    }

    /** Draw the figure at the current cursor. injuredParts holds part ids to tint red. */
    fun render(injuredParts: Set<String>, w: Float = 120f, h: Float = 220f) {
        val startX = ImGui.getCursorPosX()
        val startY = ImGui.getCursorPosY()
        for ((part, file) in layers) {
            val tid = glId(file)
            if (tid <= 0L) continue // not loaded yet this frame; valid next frame
            ImGui.setCursorPos(startX, startY)
            if (part in injuredParts) {
                // Tinted red: uv0=(0,0) uv1=(1,1) tint=(1, 0.4, 0.4, 1)
                ImGui.image(tid, w, h, 0f, 0f, 1f, 1f, 1f, 0.4f, 0.4f, 1f)
            } else {
                ImGui.image(tid, w, h, 0f, 0f, 1f, 1f, 1f, 1f, 1f, 1f)
            }
        }
        // Advance cursor past the figure.
        ImGui.setCursorPos(startX, startY + h)
    }
}
