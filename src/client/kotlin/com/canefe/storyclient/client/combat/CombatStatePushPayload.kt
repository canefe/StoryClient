package com.canefe.storyclient.client.combat

import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * Server→client combat-state push. One per state transition per entity. Wire:
 *
 *   int    entityId
 *   byte   stateOrdinal   (0=Idle, 1=Windup, 2=Active, 3=Recovery, 4=Blocking, 5=Staggered)
 *   byte   dir            (0..3, or -1 if not applicable)
 *   short  ticksLeft      (or 0 if not applicable)
 *   short  staminaCurrent (rounded; or -1 to omit)
 *   short  staminaMax
 */
data class CombatStatePushPayload(
    val entityId: Int,
    val stateOrdinal: Int,
    val dirOrdinal: Int,
    val ticksLeft: Int,
    val staminaCurrent: Int,
    val staminaMax: Int,
) : CustomPayload {
    companion object {
        val ID = CustomPayload.Id<CombatStatePushPayload>(Identifier.of("story", "combat_state_push"))

        val CODEC: PacketCodec<PacketByteBuf, CombatStatePushPayload> =
            PacketCodec.of(
                { _, _ -> throw UnsupportedOperationException("CombatStatePushPayload is server-bound only") },
                { buf ->
                    val raw = ByteArray(buf.readableBytes())
                    buf.readBytes(raw)
                    DataInputStream(ByteArrayInputStream(raw)).use { input ->
                        CombatStatePushPayload(
                            entityId = input.readInt(),
                            stateOrdinal = input.readByte().toInt(),
                            dirOrdinal = input.readByte().toInt(),
                            ticksLeft = input.readShort().toInt(),
                            staminaCurrent = input.readShort().toInt(),
                            staminaMax = input.readShort().toInt(),
                        )
                    }
                },
            )
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}
