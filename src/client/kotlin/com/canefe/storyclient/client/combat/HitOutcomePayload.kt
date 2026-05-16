package com.canefe.storyclient.client.combat

import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * Server→client hit-outcome push. Wire:
 *   int    attackerEntityId
 *   int    defenderEntityId
 *   byte   dirOrdinal     (SwingDir.ordinal)
 *   byte   outcomeOrdinal (HitOutcome.ordinal: 0 Unblocked, 1 Parry, 2 PerfectBlock, 3 PartialBlock, 4 BadBlock)
 */
data class HitOutcomePayload(
    val attackerEntityId: Int,
    val defenderEntityId: Int,
    val dirOrdinal: Int,
    val outcomeOrdinal: Int,
) : CustomPayload {
    companion object {
        val ID = CustomPayload.Id<HitOutcomePayload>(Identifier.of("story", "combat_hit_outcome"))

        val CODEC: PacketCodec<PacketByteBuf, HitOutcomePayload> =
            PacketCodec.of(
                { _, _ -> throw UnsupportedOperationException("HitOutcomePayload is server-bound only") },
                { buf ->
                    val raw = ByteArray(buf.readableBytes())
                    buf.readBytes(raw)
                    DataInputStream(ByteArrayInputStream(raw)).use { input ->
                        HitOutcomePayload(
                            attackerEntityId = input.readInt(),
                            defenderEntityId = input.readInt(),
                            dirOrdinal = input.readByte().toInt(),
                            outcomeOrdinal = input.readByte().toInt(),
                        )
                    }
                },
            )
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}
