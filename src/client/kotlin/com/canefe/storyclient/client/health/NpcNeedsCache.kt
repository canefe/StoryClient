package com.canefe.storyclient.client.health

import com.canefe.storyclient.client.hediff.HediffPacketReceiver
import kotlinx.serialization.Serializable
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Receives the watched NPC's needs on `story:npc_needs` (S2C, DM-gated by the
 * server) and caches them keyed by characterId, so the DM Health panel can render
 * the selected character's needs (hunger, thirst, …). Values are 0..100.
 *
 * Rides the SAME DM watch as hediffs: the server pushes both for any character a
 * DM is watching via `story:hediff_watch` (see HediffRelay), so no separate watch
 * is needed here.
 */
object NpcNeedsCache {

    @Serializable
    data class NpcNeedsDTO(
        val characterId: String = "",
        val needs: Map<String, Float> = emptyMap(),
    )

    data class Payload(val data: ByteArray) : CustomPayload {
        companion object {
            val ID = CustomPayload.Id<Payload>(Identifier.of("story", "npc_needs"))
            val CODEC: PacketCodec<PacketByteBuf, Payload> = PacketCodec.of(
                { value, buf -> buf.writeBytes(value.data) },
                { buf ->
                    val bytes = ByteArray(buf.readableBytes())
                    buf.readBytes(bytes)
                    Payload(bytes)
                },
            )
        }
        override fun getId(): CustomPayload.Id<out CustomPayload> = ID
        override fun equals(other: Any?): Boolean =
            this === other || (other is Payload && data.contentEquals(other.data))
        override fun hashCode(): Int = data.contentHashCode()
    }

    private val byCharacter = ConcurrentHashMap<String, Map<String, Float>>()

    /** Needs (id -> 0..100) for [characterId], or empty if none cached yet. */
    fun get(characterId: String): Map<String, Float> = byCharacter[characterId] ?: emptyMap()

    fun register() {
        PayloadTypeRegistry.playS2C().register(Payload.ID, Payload.CODEC)
        ClientPlayNetworking.registerGlobalReceiver(Payload.ID) { payload, context ->
            context.client().execute { handleInbound(payload.data) }
        }
    }

    private fun handleInbound(data: ByteArray) {
        if (data.isEmpty()) return
        val text = String(data, Charsets.UTF_8)
        val dto = runCatching {
            HediffPacketReceiver.json.decodeFromString(NpcNeedsDTO.serializer(), text)
        }.getOrNull() ?: return
        if (dto.characterId.isEmpty()) return
        byCharacter[dto.characterId] = dto.needs
    }
}
