package com.canefe.storyclient.client.health

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Client→server hediff watch command on `story:hediff_watch` (DM-gated server
 * side). Tells the server which character's hediffs this DM wants pushed to them
 * (for the DM Health panel of the selected character).
 *
 * Opcodes:
 *   0x01 WATCH   UTF characterId
 *   0x02 UNWATCH (no payload)
 */
data class HediffWatchPayload(val data: ByteArray) : CustomPayload {
    companion object {
        val ID = CustomPayload.Id<HediffWatchPayload>(Identifier.of("story", "hediff_watch"))

        val CODEC: PacketCodec<PacketByteBuf, HediffWatchPayload> =
            PacketCodec.of(
                { value, buf -> buf.writeBytes(value.data) },
                { buf ->
                    val raw = ByteArray(buf.readableBytes())
                    buf.readBytes(raw)
                    HediffWatchPayload(raw)
                },
            )

        fun watch(characterId: String) = send {
            it.writeByte(0x01); it.writeUTF(characterId)
        }

        fun unwatch() = send {
            it.writeByte(0x02)
        }

        private fun send(write: (DataOutputStream) -> Unit) {
            val baos = ByteArrayOutputStream()
            DataOutputStream(baos).use(write)
            ClientPlayNetworking.send(HediffWatchPayload(baos.toByteArray()))
        }
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    override fun equals(other: Any?): Boolean =
        this === other || (other is HediffWatchPayload && data.contentEquals(other.data))

    override fun hashCode(): Int = data.contentHashCode()
}
