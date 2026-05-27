package com.canefe.storyclient.client.emote

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
 * Server→client payload on `story:npc_emote_icon`.
 *
 * Wire format:
 *   long uuidMostSig    (NPC's client-facing uuid; (0,0) sentinel = none)
 *   long uuidLeastSig
 *   int  entityId       (Bukkit entity id of the NPC; emote floats above this entity)
 *   UTF  emoteId        (free-form id; client allowlist drops unknowns)
 *
 * The uuid lets the pacer key emote bundles by uuid (same key as dialogue),
 * so an NPC firing emote+speak from one Lua effect bundles together. Falls
 * back to entity-id keying when the server sends a zero-uuid sentinel.
 *
 * Stock allowlisted ids in v1: cry, anger, pain, laugh, shock. Unknown ids
 * are silently dropped by [EmoteRenderer].
 */
data class NpcEmoteIconPayload(
    val npcUuid: UUID?,
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
                        out.writeLong(value.npcUuid?.mostSignificantBits ?: 0L)
                        out.writeLong(value.npcUuid?.leastSignificantBits ?: 0L)
                        out.writeInt(value.entityId)
                        out.writeUTF(value.emoteId)
                    }
                    buf.writeBytes(baos.toByteArray())
                },
                { buf ->
                    val raw = ByteArray(buf.readableBytes())
                    buf.readBytes(raw)
                    DataInputStream(ByteArrayInputStream(raw)).use { input ->
                        val most = input.readLong()
                        val least = input.readLong()
                        val uuid = if (most == 0L && least == 0L) null else UUID(most, least)
                        val entityId = input.readInt()
                        val emoteId = input.readUTF()
                        NpcEmoteIconPayload(uuid, entityId, emoteId)
                    }
                },
            )
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}
