package com.canefe.storyclient.client.health

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * C2S player self-tend command on `story:tend_wound`.
 *   byte 0x01 TEND  UTF woundKey  UTF medicineId
 */
data class TendWoundPayload(val data: ByteArray) : CustomPayload {
    companion object {
        val ID = CustomPayload.Id<TendWoundPayload>(Identifier.of("story", "tend_wound"))

        val CODEC: PacketCodec<PacketByteBuf, TendWoundPayload> =
            PacketCodec.of(
                { value, buf -> buf.writeBytes(value.data) },
                { buf ->
                    val raw = ByteArray(buf.readableBytes())
                    buf.readBytes(raw)
                    TendWoundPayload(raw)
                },
            )

        fun tend(woundKey: String, medicineId: String) = send {
            it.writeByte(0x01); it.writeUTF(woundKey); it.writeUTF(medicineId)
        }

        private fun send(write: (DataOutputStream) -> Unit) {
            val baos = ByteArrayOutputStream()
            DataOutputStream(baos).use(write)
            ClientPlayNetworking.send(TendWoundPayload(baos.toByteArray()))
        }
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    override fun equals(other: Any?): Boolean =
        this === other || (other is TendWoundPayload && data.contentEquals(other.data))

    override fun hashCode(): Int = data.contentHashCode()
}
