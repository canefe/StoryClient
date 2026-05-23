package com.canefe.storyclient.client.wheel

import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * Server→client perception log push on `story:perception_log`. Sent in response
 * to a [PerceptionCommandPayload] `request` opcode. Mirrors the server-side
 * `PerceptionLogBroadcaster.encode` wire format:
 *
 *   UTF   characterId
 *   short count
 *   for each entry:
 *     UTF    source
 *     UTF    description
 *     long   timestamp
 *     UTF    perceiverId
 *     double distance
 */
data class PerceptionLogEntry(
    val source: String,
    val description: String,
    val timestamp: Long,
    val perceiverId: String,
    val distance: Double,
)

data class PerceptionLogPayload(
    val characterId: String,
    val entries: List<PerceptionLogEntry>,
) : CustomPayload {
    companion object {
        val ID = CustomPayload.Id<PerceptionLogPayload>(Identifier.of("story", "perception_log"))

        val CODEC: PacketCodec<PacketByteBuf, PerceptionLogPayload> =
            PacketCodec.of(
                { _, _ -> error("client never sends perception_log") },
                { buf ->
                    val raw = ByteArray(buf.readableBytes())
                    buf.readBytes(raw)
                    DataInputStream(ByteArrayInputStream(raw)).use { input ->
                        val charId = input.readUTF()
                        val count = input.readShort().toInt() and 0xFFFF
                        val entries = ArrayList<PerceptionLogEntry>(count)
                        repeat(count) {
                            entries.add(
                                PerceptionLogEntry(
                                    source = input.readUTF(),
                                    description = input.readUTF(),
                                    timestamp = input.readLong(),
                                    perceiverId = input.readUTF(),
                                    distance = input.readDouble(),
                                ),
                            )
                        }
                        PerceptionLogPayload(charId, entries)
                    }
                },
            )
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}
