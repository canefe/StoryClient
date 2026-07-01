package com.canefe.storyclient.client.hediff

/**
 * Holds the local player's active hediffs for the right-edge HUD. Replaced
 * wholesale on each "story:hediffs" packet (full-state semantics — the server
 * only sends on change, and always sends the complete current list).
 *
 * Beyond the raw list, this tracks per-id animation timing so the HUD can fade
 * icons in when they appear and fade them out (still drawn) after they leave the
 * packet, plus an intermittent attention shake. Timing is wall-clock
 * ([System.nanoTime]) since the HUD renders per-frame, independent of game ticks.
 */
object HediffHudState {
    // Animation tuning (ms).
    const val FADE_IN_MS = 250L
    const val FADE_OUT_MS = 250L
    // Shake: a short burst every period; SHAKE_BURST is how long one wiggle lasts.
    // The period scales with severity — mild hediffs wiggle rarely (SLOW), severe
    // ones wiggle often (FAST) — to draw more attention the worse it gets.
    const val SHAKE_PERIOD_SLOW_MS = 4000L // severity 0.0
    const val SHAKE_PERIOD_FAST_MS = 1100L // severity 1.0
    const val SHAKE_BURST_MS = 450L
    const val SHAKE_AMPLITUDE = 1.6f // px peak horizontal offset

    /** Normalized 0..1 severity, falling back to [stage] when severity is unset. */
    fun severityFraction(entry: HediffEntry): Float {
        if (entry.severity > 0f) return entry.severity.coerceIn(0f, 1f)
        return when (entry.stage) {
            "Extreme" -> 1f
            "Serious" -> 0.6f
            else -> 0.25f
        }
    }

    /** Shake period (ms) for [entry], interpolated SLOW→FAST by severity. */
    fun shakePeriodMs(entry: HediffEntry): Long {
        val f = severityFraction(entry)
        return (SHAKE_PERIOD_SLOW_MS + (SHAKE_PERIOD_FAST_MS - SHAKE_PERIOD_SLOW_MS) * f).toLong()
    }

    /** A hediff plus its animation bookkeeping. */
    data class AnimEntry(
        val entry: HediffEntry,
        val addedAtMs: Long,
        var removedAtMs: Long = 0L, // 0 = still present; set when it leaves the packet
    )

    @Volatile
    private var tracked: List<AnimEntry> = emptyList()

    /** Latest raw list (kept for any callers that want the plain active set). */
    @Volatile
    var active: List<HediffEntry> = emptyList()
        private set

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    /**
     * Diffs [entries] against the tracked set: new ids get a fade-in stamp,
     * missing ids get a fade-out stamp (and keep rendering until it elapses),
     * and ids that reappear before their fade-out finishes are revived.
     */
    @Synchronized
    fun replaceAll(entries: List<HediffEntry>) {
        active = entries
        val now = nowMs()
        val incoming = entries.associateByTo(LinkedHashMap()) { it.id }
        val result = ArrayList<AnimEntry>(tracked.size + entries.size)

        // Carry forward / update existing tracked entries.
        for (a in tracked) {
            val fresh = incoming.remove(a.entry.id)
            if (fresh != null) {
                // Still present (possibly with updated severity/stage): revive if it
                // was mid-fade-out, otherwise keep its original fade-in time.
                result += a.copy(entry = fresh, removedAtMs = 0L)
            } else if (a.removedAtMs == 0L) {
                // Newly gone this packet: begin fade-out, keep drawing it.
                result += a.copy(removedAtMs = now)
            } else if (now - a.removedAtMs < FADE_OUT_MS) {
                // Mid fade-out: keep until it completes.
                result += a
            }
            // else: fade-out finished — drop it.
        }
        // Genuinely new ids (left in `incoming`): fade in.
        for ((_, e) in incoming) {
            result += AnimEntry(entry = e, addedAtMs = now)
        }
        tracked = result
    }

    @Synchronized
    fun clear() {
        active = emptyList()
        val now = nowMs()
        // Fade everything out rather than vanishing instantly.
        tracked = tracked.map { if (it.removedAtMs == 0L) it.copy(removedAtMs = now) else it }
    }

    /** Snapshot of what should currently be drawn (still includes fading-out entries). */
    @Synchronized
    fun renderSnapshot(): List<AnimEntry> {
        val now = nowMs()
        // Prune any whose fade-out has fully elapsed (in case no packet arrives to do it).
        val live = tracked.filter { it.removedAtMs == 0L || now - it.removedAtMs < FADE_OUT_MS }
        if (live.size != tracked.size) tracked = live
        return live
    }
}

data class HediffEntry(
    val id: String,
    val severity: Float,
    val label: String,
    val stage: String,
    val description: String,
    val bodyPart: String,
    val tendedQuality: Float,
    /** "hediff" (default) or "moodlet" — picks the icon folder + tooltip framing. */
    val kind: String = "hediff",
)
