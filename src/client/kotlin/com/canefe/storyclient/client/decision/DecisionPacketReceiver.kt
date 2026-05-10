package com.canefe.storyclient.client.decision

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier

object DecisionPacketReceiver {

    /** Server → client. First byte is subtype: 0x01=prompt, 0x02=observe. Rest is UTF-8 JSON. */
    data class DecisionS2CPayload(val data: ByteArray) : CustomPayload {
        companion object {
            val ID = CustomPayload.Id<DecisionS2CPayload>(Identifier.of("story", "decision"))
            val CODEC: PacketCodec<PacketByteBuf, DecisionS2CPayload> = PacketCodec.of(
                { value, buf ->
                    buf.writeBytes(value.data)
                },
                { buf ->
                    val bytes = ByteArray(buf.readableBytes())
                    buf.readBytes(bytes)
                    DecisionS2CPayload(bytes)
                }
            )
        }
        override fun getId(): CustomPayload.Id<out CustomPayload> = ID
        override fun equals(other: Any?): Boolean = other is DecisionS2CPayload && data.contentEquals(other.data)
        override fun hashCode(): Int = data.contentHashCode()
    }

    /** Client → server. Raw UTF-8 JSON bytes of decision response (no length prefix). */
    data class DecisionC2SPayload(val data: ByteArray) : CustomPayload {
        companion object {
            val ID = CustomPayload.Id<DecisionC2SPayload>(Identifier.of("story", "decision_response"))
            val CODEC: PacketCodec<PacketByteBuf, DecisionC2SPayload> = PacketCodec.of(
                { value, buf -> buf.writeBytes(value.data) },
                { buf ->
                    val bytes = ByteArray(buf.readableBytes())
                    buf.readBytes(bytes)
                    DecisionC2SPayload(bytes)
                }
            )
        }
        override fun getId(): CustomPayload.Id<out CustomPayload> = ID
        override fun equals(other: Any?): Boolean = other is DecisionC2SPayload && data.contentEquals(other.data)
        override fun hashCode(): Int = data.contentHashCode()
    }

    @Serializable
    private data class ResponsePayload(
        val decisionId: String,
        val characterId: String = "",
        val choiceId: String? = null,
        val freeformText: String? = null,
    )

    fun register() {
        PayloadTypeRegistry.playS2C().register(DecisionS2CPayload.ID, DecisionS2CPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(DecisionC2SPayload.ID, DecisionC2SPayload.CODEC)

        ClientPlayNetworking.registerGlobalReceiver(DecisionS2CPayload.ID) { payload, context ->
            context.client().execute {
                handleInbound(payload.data)
            }
        }
    }

    private fun handleInbound(data: ByteArray) {
        if (data.isEmpty()) return
        val subtype = data[0]
        val json = String(data, 1, data.size - 1, Charsets.UTF_8)

        runCatching {
            when (subtype) {
                0x01.toByte() -> {
                    val prompt = DecisionState.json.decodeFromString<DecisionPrompt>(json)
                    DecisionState.showPrompt(prompt)
                    if (DecisionState.isCritical) {
                        CinematicCameraController.start(prompt)
                    }
                }
                0x02.toByte() -> {
                    val observe = DecisionState.json.decodeFromString<DecisionObserve>(json)
                    DecisionState.showObserve(observe)
                }
                else -> {
                    println("[DecisionPacketReceiver] Unknown subtype: $subtype")
                }
            }
        }.onFailure {
            println("[DecisionPacketReceiver] Failed to parse inbound packet: ${it.message}")
        }
    }

    fun sendResponse(decisionId: String, choiceId: String?, freeformText: String?) {
        val payload = ResponsePayload(
            decisionId = decisionId,
            choiceId = choiceId,
            freeformText = freeformText,
        )
        val json = DecisionState.json.encodeToString(payload)
        println("[DecisionPacketReceiver] sendResponse decisionId=$decisionId choiceId=$choiceId freeform=${freeformText?.take(40)} json=$json")
        try {
            ClientPlayNetworking.send(DecisionC2SPayload(json.toByteArray(Charsets.UTF_8)))
            println("[DecisionPacketReceiver] ClientPlayNetworking.send OK channel=${DecisionC2SPayload.ID.id}")
        } catch (e: Exception) {
            println("[DecisionPacketReceiver] ClientPlayNetworking.send FAILED: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
        }

        DecisionState.dismiss()
        CinematicCameraController.stop()
    }
}
