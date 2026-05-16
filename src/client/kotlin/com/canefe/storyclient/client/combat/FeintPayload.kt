package com.canefe.storyclient.client.combat

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier

/** Client→server: feint during own windup. Empty payload. */
class FeintPayload : CustomPayload {
    companion object {
        val ID = CustomPayload.Id<FeintPayload>(Identifier.of("story", "feint"))

        val CODEC: PacketCodec<PacketByteBuf, FeintPayload> =
            PacketCodec.of({ _, _ -> /* empty */ }, { _ -> FeintPayload() })

        fun send() {
            ClientPlayNetworking.send(FeintPayload())
        }
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}
