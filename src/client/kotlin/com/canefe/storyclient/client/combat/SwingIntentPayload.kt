package com.canefe.storyclient.client.combat

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Client→server directional swing intent. Wire format mirrors
 * [com.canefe.story.combat.packet.SwingIntentListener]:
 *
 *   byte  dir  (0=OVERHEAD, 1=LEFT, 2=RIGHT, 3=THRUST — matches SwingDirCodec.ordinal)
 */
data class SwingIntentPayload(val data: ByteArray) : CustomPayload {
    companion object {
        val ID = CustomPayload.Id<SwingIntentPayload>(Identifier.of("story", "swing_intent"))

        val CODEC: PacketCodec<PacketByteBuf, SwingIntentPayload> =
            PacketCodec.of(
                { value, buf -> buf.writeBytes(value.data) },
                { buf ->
                    val raw = ByteArray(buf.readableBytes())
                    buf.readBytes(raw)
                    SwingIntentPayload(raw)
                },
            )

        /** Send a swing intent. [dirOrdinal] matches SwingDir.ordinal on the server. */
        fun send(dirOrdinal: Int) {
            val baos = ByteArrayOutputStream()
            DataOutputStream(baos).use { it.writeByte(dirOrdinal) }
            ClientPlayNetworking.send(SwingIntentPayload(baos.toByteArray()))
        }
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    override fun equals(other: Any?): Boolean =
        this === other || (other is SwingIntentPayload && data.contentEquals(other.data))

    override fun hashCode(): Int = data.contentHashCode()
}
