package com.canefe.storyclient.client.combat

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier

/**
 * Client→server block intent. Wire: byte dir + byte pressed (0/1).
 */
data class BlockIntentPayload(val data: ByteArray) : CustomPayload {
    companion object {
        val ID = CustomPayload.Id<BlockIntentPayload>(Identifier.of("story", "block_intent"))

        val CODEC: PacketCodec<PacketByteBuf, BlockIntentPayload> =
            PacketCodec.of(
                { value, buf -> buf.writeBytes(value.data) },
                { buf ->
                    val raw = ByteArray(buf.readableBytes())
                    buf.readBytes(raw)
                    BlockIntentPayload(raw)
                },
            )

        fun send(dirOrdinal: Int, pressed: Boolean) {
            val bytes = byteArrayOf(dirOrdinal.toByte(), if (pressed) 1 else 0)
            ClientPlayNetworking.send(BlockIntentPayload(bytes))
        }
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    override fun equals(other: Any?): Boolean =
        this === other || (other is BlockIntentPayload && data.contentEquals(other.data))

    override fun hashCode(): Int = data.contentHashCode()
}
