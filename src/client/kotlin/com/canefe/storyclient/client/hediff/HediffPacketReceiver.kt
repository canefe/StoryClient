package com.canefe.storyclient.client.hediff

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier

/**
 * Receives "story:hediffs" (S2C) carrying the local player's active hediff
 * list as raw UTF-8 JSON, and pushes it into [HediffHudState].
 */
object HediffPacketReceiver {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    data class HediffsPayload(val data: ByteArray) : CustomPayload {
        companion object {
            val ID = CustomPayload.Id<HediffsPayload>(Identifier.of("story", "hediffs"))
            val CODEC: PacketCodec<PacketByteBuf, HediffsPayload> = PacketCodec.of(
                { value, buf -> buf.writeBytes(value.data) },
                { buf ->
                    val bytes = ByteArray(buf.readableBytes())
                    buf.readBytes(bytes)
                    HediffsPayload(bytes)
                },
            )
        }
        override fun getId(): CustomPayload.Id<out CustomPayload> = ID
        override fun equals(other: Any?): Boolean =
            this === other || (other is HediffsPayload && data.contentEquals(other.data))
        override fun hashCode(): Int = data.contentHashCode()
    }

    @Serializable
    data class HediffDTO(
        val id: String,
        val severity: Float = 0f,
        val label: String = "",
        val stage: String = "Minor",
        val description: String = "",
        val bodyPart: String = "",
        val tendedQuality: Float = 0f,
    )

    @Serializable
    data class HediffsDTO(val hediffs: List<HediffDTO> = emptyList())

    fun register() {
        PayloadTypeRegistry.playS2C().register(HediffsPayload.ID, HediffsPayload.CODEC)
        ClientPlayNetworking.registerGlobalReceiver(HediffsPayload.ID) { payload, context ->
            context.client().execute { handleInbound(payload.data) }
        }
    }

    private fun handleInbound(data: ByteArray) {
        if (data.isEmpty()) {
            ConditionHudFeed.setHediffs(emptyList())
            return
        }
        val text = String(data, Charsets.UTF_8)
        val dto = runCatching { json.decodeFromString(HediffsDTO.serializer(), text) }.getOrNull()
        if (dto == null) {
            println("[HediffPacketReceiver] Failed to parse: ${text.take(200)}")
            return
        }
        ConditionHudFeed.setHediffs(
            dto.hediffs.map {
                HediffEntry(
                    id = it.id,
                    severity = it.severity,
                    label = it.label,
                    stage = it.stage,
                    description = it.description,
                    bodyPart = it.bodyPart,
                    tendedQuality = it.tendedQuality,
                )
            },
        )
    }
}
