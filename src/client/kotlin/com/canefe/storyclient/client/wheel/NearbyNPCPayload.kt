package com.canefe.storyclient.client.wheel

import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.UUID

/**
 * Server→client payload on `story:nearby_npcs`.
 *
 * Wire format must mirror server-side `NearbyNPCBroadcaster.encode`:
 *   short  count
 *   for each:
 *     byte entityType        (0 = npc, 1 = player)
 *     long uuidMost, long uuidLeast
 *     UTF  name               (per-perceiver: real name if known, else descriptor)
 *     UTF  characterId
 *     UTF  descriptor         (always the fallback label)
 *     UTF  realName           (DM-only; "" for non-DM perceivers)
 *     short hp                 (DM-only; -1 for non-DM perceivers)
 *     short maxHp              (DM-only; -1 for non-DM perceivers)
 *     bool canSpeakAs
 *     bool isFollowing
 */
data class NearbyNPCPayload(val entries: List<NearbyNPCCache.Entry>) : CustomPayload {
    companion object {
        val ID = CustomPayload.Id<NearbyNPCPayload>(Identifier.of("story", "nearby_npcs"))

        val CODEC: PacketCodec<PacketByteBuf, NearbyNPCPayload> =
            PacketCodec.of(
                { value, buf -> writeFully(value, buf) },
                { buf -> readFully(buf) },
            )

        private fun writeFully(value: NearbyNPCPayload, buf: PacketByteBuf) {
            // Client never sends this; encode is implemented for completeness.
            buf.writeShort(value.entries.size)
            for (e in value.entries) {
                buf.writeByte(e.entityType.toInt())
                buf.writeLong(e.uuid.mostSignificantBits)
                buf.writeLong(e.uuid.leastSignificantBits)
                buf.writeString(e.name)
                buf.writeString(e.characterId)
                buf.writeString(e.descriptor)
                buf.writeString(e.shortLabel)
                buf.writeString(e.realName)
                buf.writeShort(e.hp)
                buf.writeShort(e.maxHp)
                buf.writeBoolean(e.canSpeakAs)
                buf.writeBoolean(e.isFollowing)
            }
        }

        private fun readFully(buf: PacketByteBuf): NearbyNPCPayload {
            val raw = ByteArray(buf.readableBytes())
            buf.readBytes(raw)
            val entries = mutableListOf<NearbyNPCCache.Entry>()
            DataInputStream(ByteArrayInputStream(raw)).use { input ->
                val count = input.readShort().toInt() and 0xFFFF
                repeat(count) {
                    val entityType = input.readByte()
                    val most = input.readLong()
                    val least = input.readLong()
                    val name = input.readUTF()
                    val charId = input.readUTF()
                    val descriptor = input.readUTF()
                    val shortLabel = input.readUTF()
                    val realName = input.readUTF()
                    val hp = input.readShort().toInt()
                    val maxHp = input.readShort().toInt()
                    val canSpeak = input.readBoolean()
                    val isFollowing = input.readBoolean()
                    entries.add(
                        NearbyNPCCache.Entry(
                            uuid = UUID(most, least),
                            name = name,
                            characterId = charId,
                            descriptor = descriptor,
                            shortLabel = shortLabel,
                            realName = realName,
                            hp = hp,
                            maxHp = maxHp,
                            canSpeakAs = canSpeak,
                            isFollowing = isFollowing,
                            entityType = entityType,
                        ),
                    )
                }
            }
            return NearbyNPCPayload(entries)
        }
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}
