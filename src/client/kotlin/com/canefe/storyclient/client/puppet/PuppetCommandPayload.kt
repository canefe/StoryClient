package com.canefe.storyclient.client.puppet

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Client→server puppet command. Wire format matches PuppetCommandListener:
 *
 *   byte   opcode
 *   ... opcode-specific payload
 *
 * Opcodes:
 *   0x01 MOVE_TO     UTF world, double x, double y, double z
 *   0x02 ADD         UTF npcName
 *   0x03 REMOVE      UTF npcName
 *   0x04 TOGGLE      UTF npcName
 *   0x05 CLEAR
 *   0x06 SPEAK_AT    UTF targetName, UTF text
 *
 * The payload carries pre-encoded bytes so wire format stays in one place
 * and we don't fight Fabric's PacketByteBuf when emitting structured data.
 */
data class PuppetCommandPayload(val data: ByteArray) : CustomPayload {
    companion object {
        val ID = CustomPayload.Id<PuppetCommandPayload>(Identifier.of("story", "puppet_command"))

        val CODEC: PacketCodec<PacketByteBuf, PuppetCommandPayload> =
            PacketCodec.of(
                { value, buf -> buf.writeBytes(value.data) },
                { buf ->
                    val raw = ByteArray(buf.readableBytes())
                    buf.readBytes(raw)
                    PuppetCommandPayload(raw)
                },
            )

        fun moveTo(world: String, x: Double, y: Double, z: Double) =
            send {
                it.writeByte(0x01)
                it.writeUTF(world)
                it.writeDouble(x); it.writeDouble(y); it.writeDouble(z)
            }

        fun add(name: String) = send { it.writeByte(0x02); it.writeUTF(name) }

        fun remove(name: String) = send { it.writeByte(0x03); it.writeUTF(name) }

        fun toggle(name: String) = send { it.writeByte(0x04); it.writeUTF(name) }

        fun clear() = send { it.writeByte(0x05) }

        fun speakAt(targetName: String, text: String) =
            send {
                it.writeByte(0x06); it.writeUTF(targetName); it.writeUTF(text)
            }

        private fun send(write: (DataOutputStream) -> Unit) {
            val baos = ByteArrayOutputStream()
            DataOutputStream(baos).use(write)
            ClientPlayNetworking.send(PuppetCommandPayload(baos.toByteArray()))
        }
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    override fun equals(other: Any?): Boolean =
        this === other || (other is PuppetCommandPayload && data.contentEquals(other.data))

    override fun hashCode(): Int = data.contentHashCode()
}
