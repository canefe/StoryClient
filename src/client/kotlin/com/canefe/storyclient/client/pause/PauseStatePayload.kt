package com.canefe.storyclient.client.pause

import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier

/**
 * S2C `story:pause_state` — mirrors the simulation run/pause state to the client.
 * Wire layout (matches StoryMC PauseStatePacketBridge):
 *   byte paused   (0 = running, 1 = paused)
 */
data class PauseStatePayload(val paused: Boolean) : CustomPayload {
    companion object {
        val ID = CustomPayload.Id<PauseStatePayload>(Identifier.of("story", "pause_state"))
        val CODEC: PacketCodec<PacketByteBuf, PauseStatePayload> = PacketCodec.of(
            { value, buf -> buf.writeByte(if (value.paused) 1 else 0) },
            { buf -> PauseStatePayload(buf.readByte().toInt() != 0) },
        )
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}
