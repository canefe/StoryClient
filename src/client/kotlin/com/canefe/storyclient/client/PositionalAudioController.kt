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
    @Volatile private var voice: PositionalVoice? = null
    @Volatile private var stopped = false
    private var positionUpdateTimer: Timer? = null

    // Cache entity lookup to avoid searching every tick
    private var cachedEntityId: Int? = null

    companion object {
        private const val POSITION_UPDATE_RATE = 50L // ms (once per tick)
    }

    private val maxDistance get() = StoryClientConfig.maxAudioDistance
    private val minDistance get() = StoryClientConfig.minAudioDistance

    fun start() {
        if (voice != null) return
        stopped = false

        val v = PositionalVoice.fromWavBytes(npcUuid, wavData)
        voice = v
        AudioMixer.addVoice(v)

        positionUpdateTimer = timer(period = POSITION_UPDATE_RATE, daemon = true) {
            if (!stopped) updatePosition()
        }
    }

    fun stop() {
        stopped = true
        positionUpdateTimer?.cancel()
        positionUpdateTimer = null
        voice?.let { AudioMixer.removeVoice(it) }
        voice = null
        cachedEntityId = null
    }

    private fun updatePosition() {
        val v = voice ?: return

        try {
            val client = MinecraftClient.getInstance()
            val player = client.player ?: return
            val world = client.world ?: return

            val entity = findEntityByUuid(world, player) ?: run {
                // Entity gone — mute but don't stop, it might reappear
                v.volume = 0f
                return
            }

            val distance = entity.pos.distanceTo(player.pos)

            if (distance > maxDistance) {
                v.volume = 0f
                return
            }

            val relativePos = entity.pos.subtract(player.pos)
            val distanceVolume = calculateVolumeFromDistance(distance)

            val masterVolume = client.options.getSoundVolume(SoundCategory.MASTER)
            val voiceVolume = client.options.getSoundVolume(SoundCategory.VOICE)
            val combinedVolume = (masterVolume * voiceVolume).toDouble().pow(0.5).toFloat()

            v.volume = (distanceVolume * combinedVolume).coerceIn(0f, 1f)
            v.pan = calculateStereoPan(relativePos, player.yaw)
        } catch (_: Exception) {
            // Silently handle any thread-safety issues accessing MC state
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

    private fun findEntityByUuid(world: net.minecraft.world.World, player: Entity): Entity? {
        // Try cached entity ID first (fast path)
        cachedEntityId?.let { id ->
            world.getEntityById(id)?.let { return it }
            cachedEntityId = null // Cache miss, clear it
        }

        // Fallback: search by UUID
        val searchBox = Box.of(
            player.pos,
            maxDistance * 2, maxDistance * 2, maxDistance * 2
        )

        val found = world.getOtherEntities(null, searchBox) {
            it.uuidAsString == npcUuid
        }.firstOrNull()

        // Cache the entity ID for future lookups
        found?.let { cachedEntityId = it.id }
        return found
    }
}