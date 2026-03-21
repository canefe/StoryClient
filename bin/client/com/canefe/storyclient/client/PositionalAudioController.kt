package com.canefe.storyclient.client

import net.minecraft.client.MinecraftClient
import net.minecraft.entity.Entity
import net.minecraft.sound.SoundCategory
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import java.util.*
import kotlin.concurrent.timer
import kotlin.math.*

class PositionalAudioController(
    private val wavData: ByteArray,
    private val npcUuid: String
) {
    private var voice: PositionalVoice? = null
    private var positionUpdateTimer: Timer? = null

    companion object {
        private const val POSITION_UPDATE_RATE = 5L // ms
    }

    private val maxDistance get() = StoryClientConfig.maxAudioDistance
    private val minDistance get() = StoryClientConfig.minAudioDistance

    fun start() {
        if (voice != null) return

        val v = PositionalVoice.fromWavBytes(npcUuid, wavData)
        voice = v
        AudioMixer.addVoice(v)

        positionUpdateTimer = timer(period = POSITION_UPDATE_RATE, daemon = true) {
            updatePosition()
        }
    }

    fun stop() {
        positionUpdateTimer?.cancel()
        positionUpdateTimer = null

        voice?.let { AudioMixer.removeVoice(it) }
        voice = null
    }

    private fun updatePosition() {
        val v = voice ?: return

        try {
            val client = MinecraftClient.getInstance()
            val player = client.player ?: return
            val world = client.world ?: return

            val entity = findEntityByUuid(npcUuid) ?: run {
                stop()
                return
            }

            val distance = entity.pos.distanceTo(player.pos)
            val relativePos = entity.pos.subtract(player.pos)

            if (distance > maxDistance) {
                stop()
                return
            }

            val distanceVolume = calculateVolumeFromDistance(distance)

            val minecraftVolume = MinecraftClient.getInstance()
                .options.getSoundVolume(SoundCategory.VOICE)
            val adjustedMinecraftVolume = minecraftVolume.toDouble().pow(0.5).toFloat()

            v.volume = (distanceVolume * adjustedMinecraftVolume).coerceIn(0f, 1f)
            v.pan = calculateStereoPan(relativePos, player.yaw)
        } catch (_: Exception) {
        }
    }

    private fun calculateVolumeFromDistance(distance: Double): Float {
        return when {
            distance <= minDistance -> 1.0f
            distance >= maxDistance -> 0.0f
            else -> {
                val normalized = (distance - minDistance) / (maxDistance - minDistance)
                (1.0f - normalized.toFloat().pow(2))
            }
        }
    }

    private fun calculateStereoPan(relativePosition: Vec3d, playerYaw: Float): Float {
        val angleToSound = atan2(relativePosition.z, relativePosition.x)
        val playerYawRad = Math.toRadians((playerYaw + 90).toDouble())

        var relativeAngle = angleToSound - playerYawRad
        while (relativeAngle > Math.PI) relativeAngle -= 2 * Math.PI
        while (relativeAngle < -Math.PI) relativeAngle += 2 * Math.PI

        return sin(relativeAngle).toFloat().coerceIn(-1.0f, 1.0f)
    }

    private fun findEntityByUuid(uuid: String): Entity? {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return null
        val world = client.world ?: return null

        val searchBox = Box.of(
            player.pos,
            maxDistance * 2, maxDistance * 2, maxDistance * 2
        )

        return world.getOtherEntities(null, searchBox) {
            it.uuidAsString == uuid
        }.firstOrNull()
    }
}