package com.canefe.storyclient.client.wheel

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Client→server perception command on `story:perception_command`.
 *
 * Opcodes:
 *   0x01 REQUEST UTF characterId
 *   0x02 FORGET  UTF characterId, int index
 */
data class PerceptionCommandPayload(val data: ByteArray) : CustomPayload {
    companion object {
        val ID = CustomPayload.Id<PerceptionCommandPayload>(Identifier.of("story", "perception_command"))

        val CODEC: PacketCodec<PacketByteBuf, PerceptionCommandPayload> =
            PacketCodec.of(
                { value, buf -> buf.writeBytes(value.data) },
                { buf ->
                    val raw = ByteArray(buf.readableBytes())
                    buf.readBytes(raw)
                    PerceptionCommandPayload(raw)
                },
            )

        fun request(characterId: String) =
            send {
                it.writeByte(0x01); it.writeUTF(characterId)
            }

        fun forget(characterId: String, index: Int) =
            send {
                it.writeByte(0x02); it.writeUTF(characterId); it.writeInt(index)
            }

        private fun send(write: (DataOutputStream) -> Unit) {
            val baos = ByteArrayOutputStream()
            DataOutputStream(baos).use(write)
            ClientPlayNetworking.send(PerceptionCommandPayload(baos.toByteArray()))
        }
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    override fun equals(other: Any?): Boolean =
        this === other || (other is PerceptionCommandPayload && data.contentEquals(other.data))

    override fun hashCode(): Int = data.contentHashCode()
}
