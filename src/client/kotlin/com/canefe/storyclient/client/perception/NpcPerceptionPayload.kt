package com.canefe.storyclient.client.perception

import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.UUID

enum class PopupType(val id: Byte) {
    PERCEPTION(0),
    COMBAT_ATTACK(1),
    COMBAT_ATTACKED(2),
    MOOD(3),
    AGGRESSION(4);

    companion object {
        fun fromId(id: Byte): PopupType = entries.firstOrNull { it.id == id } ?: PERCEPTION
    }
}

/**
 * Server→client payload on `story:npc_perception`.
 *
 * Wire format:
 *   long  npcUuidMost
 *   long  npcUuidLeast
 *   byte  type            (PopupType ordinal)
 *   UTF   perceivedLabel
 */
data class NpcPerceptionPayload(
    val npcUuid: UUID,
    val type: PopupType,
    val perceivedLabel: String,
) : CustomPayload {

    companion object {
        val ID = CustomPayload.Id<NpcPerceptionPayload>(Identifier.of("story", "npc_perception"))

        val CODEC: PacketCodec<PacketByteBuf, NpcPerceptionPayload> =
            PacketCodec.of(
                { value, buf ->
                    buf.writeLong(value.npcUuid.mostSignificantBits)
                    buf.writeLong(value.npcUuid.leastSignificantBits)
                    buf.writeByte(value.type.id.toInt())
                    buf.writeString(value.perceivedLabel)
                },
                { buf ->
                    val raw = ByteArray(buf.readableBytes())
                    buf.readBytes(raw)
                    DataInputStream(ByteArrayInputStream(raw)).use { input ->
                        val most = input.readLong()
                        val least = input.readLong()
                        val type = PopupType.fromId(input.readByte())
                        val label = input.readUTF()
                        NpcPerceptionPayload(UUID(most, least), type, label)
                    }
                },
            )
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}
