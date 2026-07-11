package com.canefe.storyclient.client.inventory

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier

/**
 * Receives "story:inv_widget" (S2C) carrying the inventory corner widget's rows
 * as raw UTF-8 JSON, and pushes them into [InventoryWidgetState]. Full-state
 * semantics: each packet replaces the entire row list.
 */
object InventoryWidgetPacketReceiver {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    data class WidgetPayload(val data: ByteArray) : CustomPayload {
        companion object {
            val ID = CustomPayload.Id<WidgetPayload>(Identifier.of("story", "inv_widget"))
            val CODEC: PacketCodec<PacketByteBuf, WidgetPayload> = PacketCodec.of(
                { value, buf -> buf.writeBytes(value.data) },
                { buf ->
                    val bytes = ByteArray(buf.readableBytes())
                    buf.readBytes(bytes)
                    WidgetPayload(bytes)
                },
            )
        }
        override fun getId(): CustomPayload.Id<out CustomPayload> = ID
        override fun equals(other: Any?): Boolean =
            this === other || (other is WidgetPayload && data.contentEquals(other.data))
        override fun hashCode(): Int = data.contentHashCode()
    }

    @Serializable
    data class RowDTO(
        val icon: String = "",
        val label: String = "",
        val value: String = "",
        val bar: Float? = null,
    )

    @Serializable
    data class WidgetDTO(val rows: List<RowDTO> = emptyList())

    fun register() {
        PayloadTypeRegistry.playS2C().register(WidgetPayload.ID, WidgetPayload.CODEC)
        ClientPlayNetworking.registerGlobalReceiver(WidgetPayload.ID) { payload, context ->
            context.client().execute { handleInbound(payload.data) }
        }
    }

    private fun handleInbound(data: ByteArray) {
        if (data.isEmpty()) {
            InventoryWidgetState.clear()
            return
        }
        val text = String(data, Charsets.UTF_8)
        val dto = runCatching { json.decodeFromString(WidgetDTO.serializer(), text) }.getOrNull()
        if (dto == null) {
            println("[InventoryWidgetPacketReceiver] Failed to parse: ${text.take(200)}")
            return
        }
        InventoryWidgetState.set(
            dto.rows.map {
                InventoryWidgetState.WidgetRow(
                    icon = it.icon,
                    label = it.label,
                    value = it.value,
                    bar = it.bar,
                )
            },
        )
    }
}
