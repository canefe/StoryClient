package com.canefe.storyclient.client

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import java.io.ByteArrayInputStream

/**
 * A single NPC voice fed to the software mixer. The client applies only spatial
 * effects — volume (distance) and pan (direction). Per-character voice DSP
 * (pitch/gain/low-pass/tone) is baked into the audio SERVER-SIDE by StoryMC's
 * VoiceFxProcessor, so the client just plays what it receives.
 */
class PositionalVoice(
    val id: String,
    private val stream: AudioInputStream
) {
    @Volatile var volume: Float = 1.0f   // 0..1
    @Volatile var pan: Float = 0.0f     // -1..1
    @Volatile private var closed = false

    private val tempBuf = ByteArray(AudioMixer.FRAME_SIZE * 1024)

    /**
     * Called from the mixer thread. Returns false when finished.
     */
    fun mixInto(
        mixLeft: IntArray,
        mixRight: IntArray,
        framesRequested: Int
    ): Boolean {
        if (closed) return false

        val frameSize = AudioMixer.FRAME_SIZE
        val bytesRequested = framesRequested * frameSize
        val bytesRead = stream.read(tempBuf, 0, minOf(bytesRequested, tempBuf.size))

        if (bytesRead <= 0) return false

        val framesRead = bytesRead / frameSize
        if (framesRead == 0) return true

        val v = volume.coerceIn(0f, 1f)
        val p = pan.coerceIn(-1f, 1f)

        val leftGain: Float
        val rightGain: Float

        if (p < 0f) {
            leftGain = v
            rightGain = v * (1f + p) // p in [-1,0]
        } else {
            leftGain = v * (1f - p)  // p in [0,1]
            rightGain = v
        }

        var idx = 0
        for (i in 0 until framesRead) {
            val l = ((tempBuf[idx].toInt() and 0xFF) or
                    (tempBuf[idx + 1].toInt() shl 8)).toShort().toInt()
            val r = ((tempBuf[idx + 2].toInt() and 0xFF) or
                    (tempBuf[idx + 3].toInt() shl 8)).toShort().toInt()
            idx += 4

            mixLeft[i] += (l * leftGain).toInt()
            mixRight[i] += (r * rightGain).toInt()
        }

        return true
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            stream.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        fun fromWavBytes(
            id: String,
            wavBytes: ByteArray
        ): PositionalVoice {
            val bais = ByteArrayInputStream(wavBytes)
            val wavStream = AudioSystem.getAudioInputStream(bais)

            val targetFormat = AudioMixer.format
            val convertedStream =
                if (formatsEqual(wavStream.format, targetFormat)) {
                    wavStream
                } else {
                    AudioSystem.getAudioInputStream(targetFormat, wavStream)
                }

            return PositionalVoice(id, convertedStream)
        }

        private fun formatsEqual(a: AudioFormat, b: AudioFormat): Boolean {
            return a.channels == b.channels &&
                    a.sampleRate == b.sampleRate &&
                    a.sampleSizeInBits == b.sampleSizeInBits &&
                    a.encoding == b.encoding &&
                    a.isBigEndian == b.isBigEndian
        }
    }
}
