package com.canefe.storyclient.client.puppet

import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * Server→client puppet group state. Sent on every group change.
 *
 * Wire format mirrors PuppetGroupBroadcaster.encode:
 *   short  count
 *   for each: UTF name
 */
data class PuppetGroupPayload(val names: List<String>) : CustomPayload {
    companion object {
        val ID = CustomPayload.Id<PuppetGroupPayload>(Identifier.of("story", "puppet_group"))

        val CODEC: PacketCodec<PacketByteBuf, PuppetGroupPayload> =
            PacketCodec.of(
                { value, buf ->
                    buf.writeShort(value.names.size)
                    for (n in value.names) buf.writeString(n)
                },
                { buf ->
                    val raw = ByteArray(buf.readableBytes())
                    buf.readBytes(raw)
                    val names = mutableListOf<String>()
                    DataInputStream(ByteArrayInputStream(raw)).use { input ->
                        val count = input.readShort().toInt() and 0xFFFF
                        repeat(count) { names.add(input.readUTF()) }
                    }
                    PuppetGroupPayload(names)
                },
            )
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}
