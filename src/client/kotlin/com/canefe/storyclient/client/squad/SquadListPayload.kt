package com.canefe.storyclient.client.squad

import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.UUID

/**
 * Server→client commandable-squads list. Wire format mirrors
 * [com.canefe.story.npc.squad.SquadListBroadcaster.encode]:
 *
 *   short  count
 *   for each:
 *     UTF  squadId
 *     UTF  name
 *     int  rgbColor
 *     short memberCount
 *     UTF  currentOrderLabel
 *     short memberUuidCount
 *     for each:
 *       long mostSig, long leastSig
 */
data class SquadListPayload(val entries: List<SquadListCache.Entry>) : CustomPayload {
    companion object {
        val ID = CustomPayload.Id<SquadListPayload>(Identifier.of("story", "squad_list"))

        val CODEC: PacketCodec<PacketByteBuf, SquadListPayload> =
            PacketCodec.of(
                { _, _ ->
                    // Client never sends this; encode unimplemented.
                    throw UnsupportedOperationException("SquadListPayload is server-bound only")
                },
                { buf -> readFully(buf) },
            )

        private fun readFully(buf: PacketByteBuf): SquadListPayload {
            val raw = ByteArray(buf.readableBytes())
            buf.readBytes(raw)
            val out = mutableListOf<SquadListCache.Entry>()
            DataInputStream(ByteArrayInputStream(raw)).use { input ->
                val count = input.readShort().toInt() and 0xFFFF
                repeat(count) {
                    val id = input.readUTF()
                    val name = input.readUTF()
                    val color = input.readInt()
                    val memberCount = input.readShort().toInt() and 0xFFFF
                    val orderLabel = input.readUTF()
                    val formationLabel = input.readUTF()
                    val muCount = input.readShort().toInt() and 0xFFFF
                    val members = mutableListOf<UUID>()
                    repeat(muCount) {
                        val mu = input.readLong()
                        val ml = input.readLong()
                        members.add(UUID(mu, ml))
                    }
                    out.add(SquadListCache.Entry(id, name, color, memberCount, orderLabel, formationLabel, members))
                }
            }
            return SquadListPayload(out)
        }
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}
