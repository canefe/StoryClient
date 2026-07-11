package com.canefe.storyclient.client.tips

import com.canefe.storyclient.client.ui.UIMessages
import com.google.gson.Gson
import net.minecraft.client.MinecraftClient
import java.io.File

/**
 * Central tips center: shows each registered [Tip] at most once ever, persisting
 * the seen-set to its own file (`config/storyclient-tips.json`, separate from the
 * settings config). Reset via [resetProgress] (wired to the config-menu link /
 * client command) so every tip can fire again.
 *
 * Trigger points call [show] with a tip id at a teachable moment; the manager
 * guards on the seen-set and the tip registry, renders via [UIMessages], and only
 * marks the tip seen if it was actually displayed (a tip fired with no local
 * player is not consumed).
 *
 * [file] is injectable so the persistence logic is unit-testable without a
 * Minecraft runtime.
 */
class TipManagerImpl(private val file: File) {

    private val gson = Gson()
    private val seen = LinkedHashSet<String>()

    fun load() {
        seen.clear()
        if (!file.exists()) return
        runCatching {
            val data = gson.fromJson(file.readText(), TipData::class.java)
            data?.seen?.let { seen.addAll(it) }
        }
    }

    fun save() {
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(TipData(seen.toList())))
    }

    fun hasSeen(id: String): Boolean = id in seen

    fun markSeen(id: String) {
        if (seen.add(id)) save()
    }

    fun resetProgress() {
        seen.clear()
        save()
    }

    /** Number of distinct tips seen (for diagnostics / future readouts). */
    fun seenCount(): Int = seen.size

    /**
     * Show the tip [id] if it exists and hasn't been seen. Renders via the
     * matching [UIMessages] preset and marks it seen — but ONLY if a local player
     * was present to see it (otherwise it stays unseen and can fire later).
     */
    fun show(id: String) {
        if (hasSeen(id)) return
        val tip = Tips[id] ?: return
        // No player → the message no-ops; don't consume the tip.
        if (MinecraftClient.getInstance().player == null) return

        when (tip.style) {
            TipStyle.TOAST -> UIMessages.toast(tip.title, tip.subtitle, tip.durationSecs)
            TipStyle.POPUP -> UIMessages.popup(tip.title, tip.subtitle, tip.durationSecs)
        }
        markSeen(id)
    }

    private data class TipData(val seen: List<String> = emptyList())
}

/**
 * Process-wide tips center, backed by the real config file. Delegates to a
 * [TipManagerImpl] so tests can construct their own instance with a temp file.
 */
object TipManager {
    private val impl = TipManagerImpl(File("config/storyclient-tips.json"))

    fun load() = impl.load()

    fun show(id: String) = impl.show(id)

    fun hasSeen(id: String): Boolean = impl.hasSeen(id)

    fun resetProgress() = impl.resetProgress()

    fun seenCount(): Int = impl.seenCount()
}
