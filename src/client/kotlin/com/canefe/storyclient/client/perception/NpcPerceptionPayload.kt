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
    AGGRESSION(4),
    ACTION(5);

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
 *   int   entityId        (Bukkit entity id, or -1 if unknown)
 *
 * [entityId] lets the client resolve the in-world entity by id rather than by
 * uuid — required for LibsDisguises-disguised NPCs whose disguise uuid never
 * matches a client-side entity uuid. Decoding tolerates packets without the
 * trailing int (treated as -1) for backward compatibility.
 */
data class NpcPerceptionPayload(
    val npcUuid: UUID,
    val type: PopupType,
    val perceivedLabel: String,
    val entityId: Int = -1,
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
                    buf.writeInt(value.entityId)
                },
                { buf ->
                    val raw = ByteArray(buf.readableBytes())
                    buf.readBytes(raw)
                    DataInputStream(ByteArrayInputStream(raw)).use { input ->
                        val most = input.readLong()
                        val least = input.readLong()
                        val type = PopupType.fromId(input.readByte())
                        val label = input.readUTF()
                        val entityId = if (input.available() >= 4) input.readInt() else -1
                        NpcPerceptionPayload(UUID(most, least), type, label, entityId)
                    }
                },
            )
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}
