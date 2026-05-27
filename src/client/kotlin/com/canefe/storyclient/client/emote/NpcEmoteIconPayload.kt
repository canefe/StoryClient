package com.canefe.storyclient.client.emote

import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Server→client payload on `story:npc_emote_icon`.
 *
 * Wire format:
 *   int  entityId       (Bukkit entity id of the NPC; emote floats above this entity)
 *   UTF  emoteId        (free-form id; client allowlist drops unknowns)
 *
 * Stock allowlisted ids in v1: cry, anger, pain, laugh, shock. Unknown ids
 * are silently dropped by [EmoteRenderer].
 */
data class NpcEmoteIconPayload(
    val entityId: Int,
    val emoteId: String,
) : CustomPayload {

    companion object {
        val ID = CustomPayload.Id<NpcEmoteIconPayload>(Identifier.of("story", "npc_emote_icon"))

        val CODEC: PacketCodec<PacketByteBuf, NpcEmoteIconPayload> =
            PacketCodec.of(
                { value, buf ->
                    val baos = ByteArrayOutputStream()
                    DataOutputStream(baos).use { out ->
                        out.writeInt(value.entityId)
                        out.writeUTF(value.emoteId)
                    }
                    buf.writeBytes(baos.toByteArray())
                },
                { buf ->
                    val raw = ByteArray(buf.readableBytes())
                    buf.readBytes(raw)
                    DataInputStream(ByteArrayInputStream(raw)).use { input ->
                        val entityId = input.readInt()
                        val emoteId = input.readUTF()
                        NpcEmoteIconPayload(entityId, emoteId)
                    }
                },
            )
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}
