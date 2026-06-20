package com.canefe.storyclient.client.health

import net.minecraft.util.Identifier

/** Maps a hediff id to a condition icon under textures/health/common/. */
object HealthIcons {
    private const val BASE = "textures/health/common"
    private val byId = mapOf(
        "malnutrition" to "food",
        "dehydration" to "blooddropextreme",
        "blood_loss" to "blooddropextreme",
        "infection" to "disease",
        "toxin" to "toxin",
    )
    fun iconFor(id: String): Identifier {
        val name = byId[id] ?: "whiteromb"
        return Identifier.of("storyclient", "$BASE/$name.png")
    }
}
