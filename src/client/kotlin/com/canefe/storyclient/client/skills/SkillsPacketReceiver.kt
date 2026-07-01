package com.canefe.storyclient.client.skills

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier

/**
 * Receives "story:skills" (S2C) carrying the local player's skills as raw
 * UTF-8 JSON, and pushes it into [SkillsState].
 */
object SkillsPacketReceiver {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    data class SkillsPayload(val data: ByteArray) : CustomPayload {
        companion object {
            val ID = CustomPayload.Id<SkillsPayload>(Identifier.of("story", "skills"))
            val CODEC: PacketCodec<PacketByteBuf, SkillsPayload> = PacketCodec.of(
                { value, buf -> buf.writeBytes(value.data) },
                { buf ->
                    val bytes = ByteArray(buf.readableBytes())
                    buf.readBytes(bytes)
                    SkillsPayload(bytes)
                },
            )
        }
        override fun getId(): CustomPayload.Id<out CustomPayload> = ID
        override fun equals(other: Any?): Boolean =
            this === other || (other is SkillsPayload && data.contentEquals(other.data))
        override fun hashCode(): Int = data.contentHashCode()
    }

    @Serializable
    data class SkillDTO(
        val id: String,
        val label: String = "",
        val category: String = "",
        val value: Float = 0f,
        val maxValue: Float = 100f,
        val xpFraction: Float = 0f,
    )

    @Serializable
    data class SkillsDTO(val skills: List<SkillDTO> = emptyList())

    fun register() {
        PayloadTypeRegistry.playS2C().register(SkillsPayload.ID, SkillsPayload.CODEC)
        ClientPlayNetworking.registerGlobalReceiver(SkillsPayload.ID) { payload, context ->
            context.client().execute { handleInbound(payload.data) }
        }
    }

    private fun handleInbound(data: ByteArray) {
        if (data.isEmpty()) {
            SkillsState.clear()
            return
        }
        val text = String(data, Charsets.UTF_8)
        val dto = runCatching { json.decodeFromString(SkillsDTO.serializer(), text) }.getOrNull()
        if (dto == null) {
            println("[SkillsPacketReceiver] Failed to parse: ${text.take(200)}")
            return
        }
        SkillsState.replaceAll(
            dto.skills.map {
                SkillEntry(
                    id = it.id,
                    label = it.label,
                    category = it.category,
                    value = it.value,
                    maxValue = it.maxValue,
                    xpFraction = it.xpFraction,
                )
            },
        )
    }
}
