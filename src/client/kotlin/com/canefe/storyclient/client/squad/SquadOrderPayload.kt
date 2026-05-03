package com.canefe.storyclient.client.squad

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.MinecraftClient
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * Client→server squad order. One payload per squad — we send N payloads when
 * multiple squads are selected. Wire format mirrors
 * [com.canefe.story.npc.squad.SquadOrderListener]:
 *
 *   UTF   squadId
 *   byte  opcode
 *   ... opcode-specific payload
 *
 *   0x01 MOVE_TO        UTF worldKey, double x, double y, double z
 *   0x02 HOLD
 *   0x03 FOLLOW_PLAYER  long playerUuidMost, long playerUuidLeast
 *   0x04 ENGAGE         bool targetIsPlayer, long targetMost, long targetLeast
 *   0x05 IDLE
 */
data class SquadOrderPayload(val data: ByteArray) : CustomPayload {
    companion object {
        val ID = CustomPayload.Id<SquadOrderPayload>(Identifier.of("story", "squad_order"))

        val CODEC: PacketCodec<PacketByteBuf, SquadOrderPayload> =
            PacketCodec.of(
                { value, buf -> buf.writeBytes(value.data) },
                { buf ->
                    val raw = ByteArray(buf.readableBytes())
                    buf.readBytes(raw)
                    SquadOrderPayload(raw)
                },
            )

        fun moveTo(squadId: String, worldKey: String, x: Double, y: Double, z: Double, yaw: Float) =
            send(squadId) {
                it.writeByte(0x01)
                it.writeUTF(worldKey); it.writeDouble(x); it.writeDouble(y); it.writeDouble(z)
                it.writeFloat(yaw)
            }

        fun hold(squadId: String) = send(squadId) { it.writeByte(0x02) }

        fun followSelf(squadId: String) =
            send(squadId) {
                it.writeByte(0x03)
                val pid = MinecraftClient.getInstance().player?.uuid ?: UUID(0, 0)
                it.writeLong(pid.mostSignificantBits); it.writeLong(pid.leastSignificantBits)
            }

        fun engage(squadId: String, targetUuid: UUID, targetIsPlayer: Boolean) =
            send(squadId) {
                it.writeByte(0x04)
                it.writeBoolean(targetIsPlayer)
                it.writeLong(targetUuid.mostSignificantBits)
                it.writeLong(targetUuid.leastSignificantBits)
            }

        fun idle(squadId: String) = send(squadId) { it.writeByte(0x05) }

        /** ordinal: 0=LINE, 1=WEDGE, 2=COLUMN, 3=LOOSE (matches SquadFormation enum order). */
        fun setFormation(squadId: String, ordinal: Int) =
            send(squadId) {
                it.writeByte(0x06)
                it.writeByte(ordinal)
            }

        private fun send(squadId: String, write: (DataOutputStream) -> Unit) {
            val baos = ByteArrayOutputStream()
            DataOutputStream(baos).use { out ->
                out.writeUTF(squadId)
                write(out)
            }
            ClientPlayNetworking.send(SquadOrderPayload(baos.toByteArray()))
        }

        /** Send the same order to every selected squad. */
        fun forEachSelected(action: (String) -> Unit) {
            for (id in SquadCommandState.selectedSquadIds) action(id)
        }
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    override fun equals(other: Any?): Boolean =
        this === other || (other is SquadOrderPayload && data.contentEquals(other.data))

    override fun hashCode(): Int = data.contentHashCode()
}
