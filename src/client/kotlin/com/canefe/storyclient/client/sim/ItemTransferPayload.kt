package com.canefe.storyclient.client.sim

import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * Server→client item-transfer push. Wire:
 *   int   fromEntityId
 *   int   toEntityId
 *   UTF   materialId       (namespaced, e.g. "minecraft:bread")
 *   int   customModelData  (-1 = none)
 *   short qty
 *   byte  reasonOrdinal    (0 trade, 1 gift, 2 give)
 */
data class ItemTransferPayload(
    val fromEntityId: Int,
    val toEntityId: Int,
    val materialId: String,
    val customModelData: Int,
    val qty: Int,
    val reasonOrdinal: Int,
) : CustomPayload {
    companion object {
        val ID = CustomPayload.Id<ItemTransferPayload>(Identifier.of("story", "item_transfer"))

        val CODEC: PacketCodec<PacketByteBuf, ItemTransferPayload> =
            PacketCodec.of(
                { _, _ -> throw UnsupportedOperationException("ItemTransferPayload is server-bound only") },
                { buf ->
                    val raw = ByteArray(buf.readableBytes())
                    buf.readBytes(raw)
                    DataInputStream(ByteArrayInputStream(raw)).use { input ->
                        ItemTransferPayload(
                            fromEntityId = input.readInt(),
                            toEntityId = input.readInt(),
                            materialId = input.readUTF(),
                            customModelData = input.readInt(),
                            qty = input.readShort().toInt(),
                            reasonOrdinal = input.readByte().toInt(),
                        )
                    }
                },
            )
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}
