package com.canefe.storyclient.client.recognition

import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * Server→client payload on `story:recognition_set`.
 *
 * Wire format mirrors `RecognitionBroadcaster.encode`:
 *   short  count
 *   for each:
 *     UTF  characterId
 *     UTF  realName
 */
data class RecognitionSetPayload(val known: Map<String, String>) : CustomPayload {
    companion object {
        val ID = CustomPayload.Id<RecognitionSetPayload>(Identifier.of("story", "recognition_set"))

        val CODEC: PacketCodec<PacketByteBuf, RecognitionSetPayload> =
            PacketCodec.of(
                { value, buf -> writeFully(value, buf) },
                { buf -> readFully(buf) },
            )

        private fun writeFully(value: RecognitionSetPayload, buf: PacketByteBuf) {
            buf.writeShort(value.known.size)
            for ((charId, realName) in value.known) {
                buf.writeString(charId)
                buf.writeString(realName)
            }
        }

        private fun readFully(buf: PacketByteBuf): RecognitionSetPayload {
            val raw = ByteArray(buf.readableBytes())
            buf.readBytes(raw)
            val map = mutableMapOf<String, String>()
            DataInputStream(ByteArrayInputStream(raw)).use { input ->
                val count = input.readShort().toInt() and 0xFFFF
                repeat(count) {
                    val charId = input.readUTF()
                    val realName = input.readUTF()
                    map[charId] = realName
                }
            }
            return RecognitionSetPayload(map)
        }
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}
