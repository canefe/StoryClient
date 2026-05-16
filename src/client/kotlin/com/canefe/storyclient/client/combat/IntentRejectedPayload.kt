package com.canefe.storyclient.client.combat

import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/** Server→client: a swing/dir-switch/feint was rejected. Wire: UTF reason. */
data class IntentRejectedPayload(val reason: String) : CustomPayload {
    companion object {
        val ID = CustomPayload.Id<IntentRejectedPayload>(Identifier.of("story", "combat_intent_rejected"))

        val CODEC: PacketCodec<PacketByteBuf, IntentRejectedPayload> =
            PacketCodec.of(
                { _, _ -> throw UnsupportedOperationException("IntentRejectedPayload is server-bound only") },
                { buf ->
                    val raw = ByteArray(buf.readableBytes())
                    buf.readBytes(raw)
                    val reason =
                        DataInputStream(ByteArrayInputStream(raw)).use { it.readUTF() }
                    IntentRejectedPayload(reason)
                },
            )
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}
