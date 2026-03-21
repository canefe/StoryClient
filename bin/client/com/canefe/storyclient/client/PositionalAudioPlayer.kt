package com.canefe.storyclient.client

import net.minecraft.client.MinecraftClient
import net.minecraft.entity.Entity
import net.minecraft.sound.SoundCategory
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import java.io.ByteArrayInputStream
import java.util.*
import javax.sound.sampled.*
import kotlin.concurrent.timer
import kotlin.math.*

/**
 * Plays audio with 3D positional effects (distance attenuation and stereo panning)
 * at an NPC entity's location in the Minecraft world.
 */
class PositionalAudioPlayer(
    private val audioData: ByteArray,
    private val npcUuid: String,
    private val audioFormat: AudioFormat
) {
    private var sourceDataLine: SourceDataLine? = null
    private var playbackThread: Thread? = null
    private var positionUpdateTimer: Timer? = null
    @Volatile private var isPlaying = false
    @Volatile private var shouldStop = false

    companion object {
        private const val BUFFER_SIZE = 4096
        private const val POSITION_UPDATE_RATE = 5L // ms (20 Hz updates)
    }

    private val maxDistance get() = StoryClientConfig.maxAudioDistance
    private val minDistance get() = StoryClientConfig.minAudioDistance

    // Current 3D audio parameters
    @Volatile private var currentVolume = 1.0f
    @Volatile private var currentPan = 0.0f // -1.0 (left) to 1.0 (right)

    /**
     * Starts positional audio playback
     */
    fun start() {
        if (isPlaying) return

        try {
            // Create stereo format for panning
            val stereoFormat = AudioFormat(
                audioFormat.sampleRate,
                audioFormat.sampleSizeInBits,
                2, // Force stereo
                audioFormat.encoding == AudioFormat.Encoding.PCM_SIGNED,
                audioFormat.isBigEndian
            )

            val dataLineInfo = DataLine.Info(SourceDataLine::class.java, stereoFormat)
            sourceDataLine = AudioSystem.getLine(dataLineInfo) as SourceDataLine
            sourceDataLine?.open(stereoFormat, BUFFER_SIZE)
            sourceDataLine?.start()

            isPlaying = true

            // Start playback thread
            playbackThread = Thread({ playbackLoop() }, "PositionalAudio-$npcUuid")
            playbackThread?.start()

            // Start position update thread
            startPositionUpdateLoop()

            println("✅ Positional audio started for NPC: $npcUuid")
        } catch (e: Exception) {
            println("❌ Error starting positional audio: ${e.message}")
            e.printStackTrace()
            stop()
        }
    }

    /**
     * Main playback loop - reads audio data and applies 3D effects
     */
    private fun playbackLoop() {
        try {
            val audioInputStream = AudioInputStream(
                ByteArrayInputStream(audioData),
                audioFormat,
                audioData.size.toLong() / audioFormat.frameSize
            )

            // Convert to stereo if needed
            val stereoStream = if (audioFormat.channels == 1) {
                convertMonoToStereo(audioInputStream)
            } else {
                audioInputStream
            }

            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int

            while (isPlaying && !shouldStop) {
                bytesRead = stereoStream.read(buffer, 0, buffer.size)
                if (bytesRead == -1) break

                // Apply current volume and panning to buffer
                applyEffectsToBuffer(buffer, bytesRead)

                sourceDataLine?.write(buffer, 0, bytesRead)
            }

            audioInputStream.close()
        } catch (e: Exception) {
            println("❌ Error in playback loop: ${e.message}")
            e.printStackTrace()
        } finally {
            stop()
        }
    }

    /**
     * Applies volume and stereo panning effects to the audio buffer
     */
    private fun applyEffectsToBuffer(buffer: ByteArray, length: Int) {
        val sampleSize = audioFormat.sampleSizeInBits / 8
        if (sampleSize != 2) return // Only handle 16-bit audio

        val numFrames = length / (sampleSize * 2) // 2 channels

        for (i in 0 until numFrames) {
            val leftIndex = i * sampleSize * 2
            val rightIndex = leftIndex + sampleSize

            // Read 16-bit samples (little-endian)
            var leftSample = ((buffer[leftIndex].toInt() and 0xFF) or
                             (buffer[leftIndex + 1].toInt() shl 8)).toShort().toInt()
            var rightSample = ((buffer[rightIndex].toInt() and 0xFF) or
                              (buffer[rightIndex + 1].toInt() shl 8)).toShort().toInt()

            // Apply volume
            leftSample = (leftSample * currentVolume).toInt()
            rightSample = (rightSample * currentVolume).toInt()

            // Apply panning
            if (currentPan < 0) {
                // Sound is to the left - reduce right channel
                rightSample = (rightSample * (1.0f + currentPan)).toInt()
            } else if (currentPan > 0) {
                // Sound is to the right - reduce left channel
                leftSample = (leftSample * (1.0f - currentPan)).toInt()
            }

            // Clamp samples to 16-bit range
            leftSample = leftSample.coerceIn(-32768, 32767)
            rightSample = rightSample.coerceIn(-32768, 32767)

            // Write samples back (little-endian)
            buffer[leftIndex] = (leftSample and 0xFF).toByte()
            buffer[leftIndex + 1] = (leftSample shr 8).toByte()
            buffer[rightIndex] = (rightSample and 0xFF).toByte()
            buffer[rightIndex + 1] = (rightSample shr 8).toByte()
        }
    }

    /**
     * Starts the position update loop (20 Hz)
     */
    private fun startPositionUpdateLoop() {
        positionUpdateTimer = timer(period = POSITION_UPDATE_RATE, daemon = true) {
            updatePosition()
        }
    }

    /**
     * Updates volume and panning based on entity position
     */
    private fun updatePosition() {
        if (!isPlaying) return

        try {
            val entity = findEntityByUuid(npcUuid)
            if (entity == null) {
                // Entity not found or too far - stop audio
                stop()
                return
            }

            val player = MinecraftClient.getInstance().player ?: return

            val distance = entity.pos.distanceTo(player.pos)
            val relativePos = entity.pos.subtract(player.pos)

            // Calculate volume based on distance
            val distanceVolume = calculateVolumeFromDistance(distance)

            // Get Minecraft's voice volume setting
            val minecraftVolume = MinecraftClient.getInstance()
                .options.getSoundVolume(SoundCategory.VOICE)

            // Apply configured volume curve
            val adjustedMinecraftVolume = minecraftVolume.toDouble().pow(0.5).toFloat()

            // Combine volumes
            currentVolume = (distanceVolume * adjustedMinecraftVolume).coerceIn(0.0f, 1.0f)

            // Calculate panning
            currentPan = calculateStereoPan(relativePos, player.yaw)

            // If too far, stop playback
            if (distance > maxDistance) {
                println("🔇 NPC too far ($distance blocks), stopping audio")
                stop()
            }
        } catch (e: Exception) {
            println("❌ Error updating position: ${e.message}")
        }
    }

    /**
     * Calculates volume multiplier based on distance from player
     */
    private fun calculateVolumeFromDistance(distance: Double): Float {
        return when {
            distance <= minDistance -> 1.0f
            distance >= maxDistance -> 0.0f
            else -> {
                // Inverse square law for realistic sound falloff
                val normalizedDistance = (distance - minDistance) / (maxDistance - minDistance)
                (1.0f - normalizedDistance.toFloat().pow(2))
            }
        }
    }

    /**
     * Calculates stereo pan based on NPC position relative to player's facing direction
     */
    private fun calculateStereoPan(relativePosition: Vec3d, playerYaw: Float): Float {
        // Calculate angle to sound source in world coordinates
        val angleToSound = atan2(relativePosition.z, relativePosition.x)

        // Convert player yaw to radians (Minecraft yaw: 0 = south, increases clockwise)
        val playerYawRad = Math.toRadians((playerYaw + 90).toDouble())

        // Calculate relative angle
        var relativeAngle = angleToSound - playerYawRad

        // Normalize to -PI to PI
        while (relativeAngle > PI) relativeAngle -= 2 * PI
        while (relativeAngle < -PI) relativeAngle += 2 * PI

        // Convert to pan value: -1.0 = fully left, 0.0 = center, 1.0 = fully right
        return sin(relativeAngle).toFloat().coerceIn(-1.0f, 1.0f)
    }

    /**
     * Finds an entity by UUID within hearing distance
     */
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

    /**
     * Converts mono audio stream to stereo
     */
    private fun convertMonoToStereo(monoStream: AudioInputStream): AudioInputStream {
        val monoFormat = monoStream.format
        val stereoFormat = AudioFormat(
            monoFormat.sampleRate,
            monoFormat.sampleSizeInBits,
            2, // Stereo
            monoFormat.encoding == AudioFormat.Encoding.PCM_SIGNED,
            monoFormat.isBigEndian
        )

        return AudioSystem.getAudioInputStream(stereoFormat, monoStream)
    }

    /**
     * Stops playback and cleans up resources
     */
    fun stop() {
        if (!isPlaying && sourceDataLine == null) return

        shouldStop = true
        isPlaying = false

        positionUpdateTimer?.cancel()
        positionUpdateTimer = null

        try {
            sourceDataLine?.stop()
            sourceDataLine?.close()
        } catch (e: Exception) {
            // Ignore errors during shutdown
        }
        sourceDataLine = null

        playbackThread?.interrupt()
        playbackThread = null

        println("🛑 Stopped positional audio for NPC: $npcUuid")
    }
}
