package com.canefe.storyclient.client.permission

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier

/**
 * Carries the story-go DM-permission ask flow into the StoryClient toast HUD.
 *
 * Wire shape (both directions): a single CustomPayload carrying raw UTF-8
 * JSON bytes. Matches DecisionPacketReceiver's "bytes are JSON" convention
 * but without a subtype prefix — there is only one message per direction.
 *
 *   S2C ("story:permission_prompt")  PermissionPromptPayload  JSON: { requestId, trigger, description, timeoutSec }
 *   C2S ("story:permission_response") PermissionResponsePayload JSON: { requestId, accepted }
 *
 * The server-side TaskManager is the source of truth for accept/refuse and
 * timeout. The toast is purely a parallel UX: pressing Y or N here causes
 * the plugin to call taskManager.acceptTask / refuseTask on its end, which
 * still emits PermissionResponseEvent over the WebSocket to story-go.
 */
object PermissionPacketReceiver {

    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Server → client: open a toast. */
    data class PermissionPromptPayload(val data: ByteArray) : CustomPayload {
        companion object {
            val ID = CustomPayload.Id<PermissionPromptPayload>(Identifier.of("story", "permission_prompt"))
            val CODEC: PacketCodec<PacketByteBuf, PermissionPromptPayload> = PacketCodec.of(
                { value, buf -> buf.writeBytes(value.data) },
                { buf ->
                    val bytes = ByteArray(buf.readableBytes())
                    buf.readBytes(bytes)
                    PermissionPromptPayload(bytes)
                },
            )
        }
        override fun getId(): CustomPayload.Id<out CustomPayload> = ID
        override fun equals(other: Any?): Boolean =
            this === other || (other is PermissionPromptPayload && data.contentEquals(other.data))
        override fun hashCode(): Int = data.contentHashCode()
    }

    /** Client → server: DM pressed accept or deny on a toast. */
    data class PermissionResponsePayload(val data: ByteArray) : CustomPayload {
        companion object {
            val ID = CustomPayload.Id<PermissionResponsePayload>(Identifier.of("story", "permission_response"))
            val CODEC: PacketCodec<PacketByteBuf, PermissionResponsePayload> = PacketCodec.of(
                { value, buf -> buf.writeBytes(value.data) },
                { buf ->
                    val bytes = ByteArray(buf.readableBytes())
                    buf.readBytes(bytes)
                    PermissionResponsePayload(bytes)
                },
            )
        }
        override fun getId(): CustomPayload.Id<out CustomPayload> = ID
        override fun equals(other: Any?): Boolean =
            this === other || (other is PermissionResponsePayload && data.contentEquals(other.data))
        override fun hashCode(): Int = data.contentHashCode()
    }

    @Serializable
    data class PromptDTO(
        val requestId: String,
        val trigger: String = "",
        val description: String = "",
        val timeoutSec: Int = 60,
    )

    @Serializable
    private data class ResponseDTO(
        val requestId: String,
        val accepted: Boolean,
    )

    fun register() {
        PayloadTypeRegistry.playS2C().register(PermissionPromptPayload.ID, PermissionPromptPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(PermissionResponsePayload.ID, PermissionResponsePayload.CODEC)

        ClientPlayNetworking.registerGlobalReceiver(PermissionPromptPayload.ID) { payload, context ->
            context.client().execute {
                handleInbound(payload.data)
            }
        }
    }

    private fun handleInbound(data: ByteArray) {
        if (data.isEmpty()) return
        val text = String(data, Charsets.UTF_8)
        val prompt = runCatching { json.decodeFromString<PromptDTO>(text) }.getOrNull()
        if (prompt == null) {
            println("[PermissionPacketReceiver] Failed to parse PromptDTO from: ${text.take(200)}")
            return
        }
        PermissionToastState.push(prompt)
    }

    /**
     * Send accept/deny back to the server. Called by the toast HUD when the
     * DM hits one of the configured keybinds.
     */
    fun sendResponse(requestId: String, accepted: Boolean) {
        val dto = ResponseDTO(requestId = requestId, accepted = accepted)
        val payload = json.encodeToString(dto).toByteArray(Charsets.UTF_8)
        try {
            ClientPlayNetworking.send(PermissionResponsePayload(payload))
        } catch (e: Exception) {
            println("[PermissionPacketReceiver] send failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
