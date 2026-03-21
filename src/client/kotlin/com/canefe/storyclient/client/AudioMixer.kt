package com.canefe.storyclient.client

import javax.sound.sampled.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.*

object AudioMixer {
    const val SAMPLE_RATE = 44100f             // was 48000f
    const val CHANNELS = 2
    const val BYTES_PER_SAMPLE = 2
    const val FRAME_SIZE = CHANNELS * BYTES_PER_SAMPLE
    private const val BUFFER_FRAMES = 1024

    val format = AudioFormat(
        SAMPLE_RATE,
        16,
        CHANNELS,
        true,    // signed
        false    // little-endian
    )

    private var line: SourceDataLine? = null
    private var mixerThread: Thread? = null
    @Volatile private var running = false

    private val voices = CopyOnWriteArrayList<PositionalVoice>()

    fun init() {
        if (running) return

        val info = DataLine.Info(SourceDataLine::class.java, format)
        line = AudioSystem.getLine(info) as SourceDataLine
        line!!.open(format, BUFFER_FRAMES * FRAME_SIZE * 4)
        line!!.start()

        running = true
        mixerThread = Thread(::mixLoop, "Story-AudioMixer").apply {
            isDaemon = true
            start()
        }
    }

    fun shutdown() {
        running = false
        mixerThread?.join(100)
        mixerThread = null

        voices.forEach { it.close() }
        voices.clear()

        line?.drain()
        line?.stop()
        line?.close()
        line = null
    }

    fun addVoice(voice: PositionalVoice) {
        init() // lazy init
        voices += voice
    }

    fun removeVoice(voice: PositionalVoice) {
        voices -= voice
        voice.close()
    }

    private fun mixLoop() {
        val mixLeft = IntArray(BUFFER_FRAMES)
        val mixRight = IntArray(BUFFER_FRAMES)
        val outBytes = ByteArray(BUFFER_FRAMES * FRAME_SIZE)

        while (running) {
            java.util.Arrays.fill(mixLeft, 0)
            java.util.Arrays.fill(mixRight, 0)

            if (voices.isEmpty()) {
                Thread.sleep(5)
                continue
            }

            val deadVoices = mutableListOf<PositionalVoice>()

            for (v in voices) {
                if (!v.mixInto(mixLeft, mixRight, BUFFER_FRAMES)) {
                    deadVoices += v
                }
            }

            if (deadVoices.isNotEmpty()) {
                deadVoices.forEach { removeVoice(it) }
            }

            var outIndex = 0
            for (i in 0 until BUFFER_FRAMES) {
                val l = mixLeft[i].coerceIn(-32768, 32767)
                val r = mixRight[i].coerceIn(-32768, 32767)

                outBytes[outIndex++] = (l and 0xFF).toByte()
                outBytes[outIndex++] = (l shr 8).toByte()
                outBytes[outIndex++] = (r and 0xFF).toByte()
                outBytes[outIndex++] = (r shr 8).toByte()
            }

            line?.write(outBytes, 0, outBytes.size)
        }
    }
}