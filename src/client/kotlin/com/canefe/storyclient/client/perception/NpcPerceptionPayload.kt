package com.canefe.storyclient.client.perception

import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.UUID

/**
 * Server→client payload on `story:npc_perception`.
 *
 * Wire format:
 *   long  npcUuidMost
 *   long  npcUuidLeast
 *   UTF   perceivedLabel   (what the NPC calls the target — resolved label)
 */
data class NpcPerceptionPayload(
    val npcUuid: UUID,
    val perceivedLabel: String,
) : CustomPayload {

    companion object {
        val ID = CustomPayload.Id<NpcPerceptionPayload>(Identifier.of("story", "npc_perception"))

        val CODEC: PacketCodec<PacketByteBuf, NpcPerceptionPayload> =
            PacketCodec.of(
                { value, buf ->
                    buf.writeLong(value.npcUuid.mostSignificantBits)
                    buf.writeLong(value.npcUuid.leastSignificantBits)
                    buf.writeString(value.perceivedLabel)
                },
                { buf ->
                    val raw = ByteArray(buf.readableBytes())
                    buf.readBytes(raw)
                    DataInputStream(ByteArrayInputStream(raw)).use { input ->
                        val most = input.readLong()
                        val least = input.readLong()
                        val label = input.readUTF()
                        NpcPerceptionPayload(UUID(most, least), label)
                    }
                },
            )
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}
