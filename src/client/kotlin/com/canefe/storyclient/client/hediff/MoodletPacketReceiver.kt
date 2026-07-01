package com.canefe.storyclient.client.hediff

import kotlinx.serialization.Serializable
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import kotlin.math.abs

/**
 * Receives "story:moodlets" (S2C) carrying the local player's active moodlets
 * (mood-flagged thoughts like "Bored") and feeds them into the shared right-edge
 * condition HUD via [ConditionHudFeed], alongside hediffs.
 *
 * Moodlets map onto [HediffEntry] with kind="moodlet" so the existing HUD renders
 * them unchanged. `stage` is derived from |moodOffset| so the backdrop tint reads
 * mild → severe the same way hediff severity does.
 */
object MoodletPacketReceiver {

    data class MoodletsPayload(val data: ByteArray) : CustomPayload {
        companion object {
            val ID = CustomPayload.Id<MoodletsPayload>(Identifier.of("story", "moodlets"))
            val CODEC: PacketCodec<PacketByteBuf, MoodletsPayload> = PacketCodec.of(
                { value, buf -> buf.writeBytes(value.data) },
                { buf ->
                    val bytes = ByteArray(buf.readableBytes())
                    buf.readBytes(bytes)
                    MoodletsPayload(bytes)
                },
            )
        }
        override fun getId(): CustomPayload.Id<out CustomPayload> = ID
        override fun equals(other: Any?): Boolean =
            this === other || (other is MoodletsPayload && data.contentEquals(other.data))
        override fun hashCode(): Int = data.contentHashCode()
    }

    @Serializable
    data class MoodletDTO(
        val id: String,
        val label: String = "",
        val moodOffset: Float = 0f,
        val description: String = "",
    )

    @Serializable
    data class MoodletsDTO(val moodlets: List<MoodletDTO> = emptyList())

    /** Discretize |mood offset| into the HUD's tint tiers (reuses hediff stages). */
    private fun stageFor(moodOffset: Float): String {
        val m = abs(moodOffset)
        return when {
            m >= 8f -> "Extreme"
            m >= 4f -> "Serious"
            else -> "Minor"
        }
    }

    fun register() {
        PayloadTypeRegistry.playS2C().register(MoodletsPayload.ID, MoodletsPayload.CODEC)
        ClientPlayNetworking.registerGlobalReceiver(MoodletsPayload.ID) { payload, context ->
            context.client().execute { handleInbound(payload.data) }
        }
    }

    private fun handleInbound(data: ByteArray) {
        if (data.isEmpty()) {
            ConditionHudFeed.setMoodlets(emptyList())
            return
        }
        val text = String(data, Charsets.UTF_8)
        val dto = runCatching {
            HediffPacketReceiver.json.decodeFromString(MoodletsDTO.serializer(), text)
        }.getOrNull()
        if (dto == null) {
            println("[MoodletPacketReceiver] Failed to parse: ${text.take(200)}")
            return
        }
        ConditionHudFeed.setMoodlets(
            dto.moodlets.map {
                HediffEntry(
                    id = it.id,
                    severity = (abs(it.moodOffset) / 10f).coerceIn(0f, 1f),
                    label = it.label,
                    stage = stageFor(it.moodOffset),
                    description = it.description,
                    bodyPart = "",
                    tendedQuality = 0f,
                    kind = "moodlet",
                    positive = it.moodOffset >= 0f,
                )
            },
        )
    }
}
