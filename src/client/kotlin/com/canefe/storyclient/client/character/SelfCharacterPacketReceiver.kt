package com.canefe.storyclient.client.character

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier

/**
 * Receives "story:char_self" (S2C) carrying the local player's own Story
 * character data (id + name) as raw UTF-8 JSON, into [SelfCharacterState].
 */
object SelfCharacterPacketReceiver {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    data class SelfCharPayload(val data: ByteArray) : CustomPayload {
        companion object {
            val ID = CustomPayload.Id<SelfCharPayload>(Identifier.of("story", "char_self"))
            val CODEC: PacketCodec<PacketByteBuf, SelfCharPayload> = PacketCodec.of(
                { value, buf -> buf.writeBytes(value.data) },
                { buf ->
                    val bytes = ByteArray(buf.readableBytes())
                    buf.readBytes(bytes)
                    SelfCharPayload(bytes)
                },
            )
        }
        override fun getId(): CustomPayload.Id<out CustomPayload> = ID
        override fun equals(other: Any?): Boolean =
            this === other || (other is SelfCharPayload && data.contentEquals(other.data))
        override fun hashCode(): Int = data.contentHashCode()
    }

    @Serializable
    data class SelfCharDTO(
        val characterId: String = "",
        val name: String = "",
    )

    fun register() {
        PayloadTypeRegistry.playS2C().register(SelfCharPayload.ID, SelfCharPayload.CODEC)
        ClientPlayNetworking.registerGlobalReceiver(SelfCharPayload.ID) { payload, context ->
            context.client().execute { handleInbound(payload.data) }
        }
    }

    private fun handleInbound(data: ByteArray) {
        println("[SelfCharacterPacketReceiver] inbound ${data.size} bytes")
        if (data.isEmpty()) {
            SelfCharacterState.clear()
            return
        }
        val text = String(data, Charsets.UTF_8)
        val dto = runCatching { json.decodeFromString(SelfCharDTO.serializer(), text) }.getOrNull()
        if (dto == null) {
            println("[SelfCharacterPacketReceiver] Failed to parse: ${text.take(200)}")
            return
        }
        println("[SelfCharacterPacketReceiver] set name='${dto.name}' id='${dto.characterId}'")
        SelfCharacterState.set(dto.characterId, dto.name)
    }
}
