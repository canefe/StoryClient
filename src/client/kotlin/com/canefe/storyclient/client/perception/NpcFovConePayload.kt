package com.canefe.storyclient.client.perception

import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * Server→client payload on `story:npc_fov`. Carries slow-changing FOV params per
 * NPC; the client reads each NPC's live pose every frame (see FovConeRenderer).
 *
 * Wire format:
 *   byte  enabled   (1 show / 0 clear)
 *   short count
 *   repeat count: long uuidMost, long uuidLeast, int entityId, float sightRange, float fovHalfDeg
 */
data class NpcFovConePayload(
    val enabled: Boolean,
    val cones: List<ConeParams>,
) : CustomPayload {

    data class ConeParams(
        val uuid: UUID,
        val entityId: Int,
        val sightRange: Float,
        val fovHalfDeg: Float,
    )

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<NpcFovConePayload>(Identifier.of("story", "npc_fov"))

        fun decodeBytes(raw: ByteArray): NpcFovConePayload =
            DataInputStream(ByteArrayInputStream(raw)).use { input ->
                val enabled = input.readByte().toInt() != 0
                val count = input.readShort().toInt()
                val cones = ArrayList<ConeParams>(count.coerceAtLeast(0))
                repeat(count) {
                    val most = input.readLong()
                    val least = input.readLong()
                    val entityId = input.readInt()
                    val sightRange = input.readFloat()
                    val fovHalfDeg = input.readFloat()
                    cones += ConeParams(UUID(most, least), entityId, sightRange, fovHalfDeg)
                }
                NpcFovConePayload(enabled, cones)
            }

        private fun encodeBytes(value: NpcFovConePayload): ByteArray {
            val baos = ByteArrayOutputStream()
            DataOutputStream(baos).use { out ->
                out.writeByte(if (value.enabled) 1 else 0)
                out.writeShort(value.cones.size)
                for (c in value.cones) {
                    out.writeLong(c.uuid.mostSignificantBits)
                    out.writeLong(c.uuid.leastSignificantBits)
                    out.writeInt(c.entityId)
                    out.writeFloat(c.sightRange)
                    out.writeFloat(c.fovHalfDeg)
                }
            }
            return baos.toByteArray()
        }

        val CODEC: PacketCodec<PacketByteBuf, NpcFovConePayload> =
            PacketCodec.of(
                { value, buf -> buf.writeBytes(encodeBytes(value)) },
                { buf ->
                    val raw = ByteArray(buf.readableBytes())
                    buf.readBytes(raw)
                    decodeBytes(raw)
                },
            )
    }
}
