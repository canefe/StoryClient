package com.canefe.storyclient.client.tips

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Persistence-logic coverage for [TipManagerImpl]. `show()` is not exercised here
 * because it touches the Minecraft runtime (MinecraftClient / UIMessages); the
 * seen-set + file round-trip is the genuinely unit-testable unit.
 */
class TipManagerTest {

    private val tmpDir = Files.createTempDirectory("tips-test").toFile()
    private fun file() = File(tmpDir, "storyclient-tips.json")

    @AfterTest
    fun cleanup() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun mark_seen_persists_and_reloads() {
        val a = TipManagerImpl(file())
        assertFalse(a.hasSeen("ooc_camera"))
        a.markSeen("ooc_camera")
        assertTrue(a.hasSeen("ooc_camera"))

        // A fresh instance over the same file sees the persisted state.
        val b = TipManagerImpl(file())
        b.load()
        assertTrue(b.hasSeen("ooc_camera"))
        assertEquals(1, b.seenCount())
    }

    @Test
    fun mark_seen_is_idempotent() {
        val m = TipManagerImpl(file())
        m.markSeen("x")
        m.markSeen("x")
        assertEquals(1, m.seenCount())
    }

    @Test
    fun reset_clears_and_persists() {
        val m = TipManagerImpl(file())
        m.markSeen("x")
        m.markSeen("y")
        assertEquals(2, m.seenCount())

        m.resetProgress()
        assertEquals(0, m.seenCount())
        assertFalse(m.hasSeen("x"))

        // Persisted empty: a reload stays empty.
        val reloaded = TipManagerImpl(file())
        reloaded.load()
        assertEquals(0, reloaded.seenCount())
    }

    @Test
    fun load_missing_file_is_empty() {
        val m = TipManagerImpl(File(tmpDir, "does-not-exist.json"))
        m.load()
        assertEquals(0, m.seenCount())
    }

    @Test
    fun load_corrupt_file_is_empty() {
        file().writeText("{ not valid json ]")
        val m = TipManagerImpl(file())
        m.load()
        assertEquals(0, m.seenCount())
    }
}
