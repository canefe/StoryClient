package com.canefe.storyclient.client.combat

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier

/** Client→server: switch swing direction mid-windup (1 byte SwingDir.ordinal). */
data class DirectionSwitchPayload(val data: ByteArray) : CustomPayload {
    companion object {
        val ID = CustomPayload.Id<DirectionSwitchPayload>(Identifier.of("story", "direction_switch"))

        val CODEC: PacketCodec<PacketByteBuf, DirectionSwitchPayload> =
            PacketCodec.of(
                { value, buf -> buf.writeBytes(value.data) },
                { buf ->
                    val raw = ByteArray(buf.readableBytes())
                    buf.readBytes(raw)
                    DirectionSwitchPayload(raw)
                },
            )

        fun send(dirOrdinal: Int) {
            ClientPlayNetworking.send(DirectionSwitchPayload(byteArrayOf(dirOrdinal.toByte())))
        }
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    override fun equals(other: Any?): Boolean =
        this === other || (other is DirectionSwitchPayload && data.contentEquals(other.data))

    override fun hashCode(): Int = data.contentHashCode()
}
