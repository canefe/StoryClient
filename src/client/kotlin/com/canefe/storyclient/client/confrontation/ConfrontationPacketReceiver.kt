package com.canefe.storyclient.client.confrontation

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.client.MinecraftClient
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier

/**
 * S2C/C2S bridge for LOCKED confrontations — the analogue of
 * [com.canefe.storyclient.client.decision.DecisionPacketReceiver].
 *
 * S2C payload: [subtype byte] + UTF-8 JSON. Subtypes match the server relay:
 * 0=enter, 1=choices, 2=turn, 3=resolve, 4=exit.
 */
object ConfrontationPacketReceiver {

    data class S2CPayload(val data: ByteArray) : CustomPayload {
        companion object {
            val ID = CustomPayload.Id<S2CPayload>(Identifier.of("story", "confrontation"))
            val CODEC: PacketCodec<PacketByteBuf, S2CPayload> = PacketCodec.of(
                { value, buf -> buf.writeBytes(value.data) },
                { buf ->
                    val bytes = ByteArray(buf.readableBytes())
                    buf.readBytes(bytes)
                    S2CPayload(bytes)
                },
            )
        }
        override fun getId(): CustomPayload.Id<out CustomPayload> = ID
        override fun equals(other: Any?): Boolean = other is S2CPayload && data.contentEquals(other.data)
        override fun hashCode(): Int = data.contentHashCode()
    }

    data class C2SPayload(val data: ByteArray) : CustomPayload {
        companion object {
            val ID = CustomPayload.Id<C2SPayload>(Identifier.of("story", "confrontation_response"))
            val CODEC: PacketCodec<PacketByteBuf, C2SPayload> = PacketCodec.of(
                { value, buf -> buf.writeBytes(value.data) },
                { buf ->
                    val bytes = ByteArray(buf.readableBytes())
                    buf.readBytes(bytes)
                    C2SPayload(bytes)
                },
            )
        }
        override fun getId(): CustomPayload.Id<out CustomPayload> = ID
        override fun equals(other: Any?): Boolean = other is C2SPayload && data.contentEquals(other.data)
        override fun hashCode(): Int = data.contentHashCode()
    }

    @Serializable
    private data class ResponseDto(
        val confrontationId: String,
        val choiceId: String? = null,
        val text: String? = null,
    )

    fun register() {
        PayloadTypeRegistry.playS2C().register(S2CPayload.ID, S2CPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(C2SPayload.ID, C2SPayload.CODEC)

        ClientPlayNetworking.registerGlobalReceiver(S2CPayload.ID) { payload, context ->
            context.client().execute { handleInbound(payload.data) }
        }
    }

    private fun handleInbound(data: ByteArray) {
        if (data.isEmpty()) return
        val subtype = data[0]
        val json = String(data, 1, data.size - 1, Charsets.UTF_8)
        runCatching {
            when (subtype) {
                0x00.toByte() -> {
                    val e = ConfrontationState.json.decodeFromString(EnterS2C.serializer(), json)
                    ConfrontationState.enter(e.id)
                    ConfrontationCameraController.start()
                }
                0x01.toByte() -> {
                    val c = ConfrontationState.json.decodeFromString(ChoicesS2C.serializer(), json)
                    // Only my turn's choices reach me (server targets them), so accept.
                    ConfrontationState.setChoices(c)
                }
                0x02.toByte() -> {
                    val t = ConfrontationState.json.decodeFromString(TurnS2C.serializer(), json)
                    ConfrontationState.setTurn(t.active_character_id)
                    if (t.active_character_id != localCharacterId()) {
                        ConfrontationState.clearMyTurn()
                    }
                }
                0x03.toByte() -> {
                    // resolve: narration/roll broadcast — overlay reads it; no state gate needed here.
                }
                0x04.toByte() -> {
                    ConfrontationState.exit()
                    ConfrontationCameraController.stop()
                }
                else -> {}
            }
        }.onFailure {
            println("[ConfrontationPacketReceiver] parse failed (subtype=$subtype): ${it.message}")
        }
    }

    private fun localCharacterId(): String? =
        MinecraftClient.getInstance().player?.uuidAsString

    fun sendPick(confrontationId: String, choiceId: String) {
        send(ResponseDto(confrontationId = confrontationId, choiceId = choiceId))
    }

    fun sendFreeText(confrontationId: String, text: String) {
        send(ResponseDto(confrontationId = confrontationId, text = text))
    }

    private fun send(dto: ResponseDto) {
        val json = ConfrontationState.json.encodeToString(dto)
        runCatching {
            ClientPlayNetworking.send(C2SPayload(json.toByteArray(Charsets.UTF_8)))
        }.onFailure {
            println("[ConfrontationPacketReceiver] send failed: ${it.message}")
        }
    }
}
