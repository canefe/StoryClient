package com.canefe.storyclient.client.hediff

/**
 * Merges the two condition sources — hediffs (story:hediffs) and moodlets
 * (story:moodlets) — into the single right-edge [HediffHudState] column, so
 * injuries and mood states share one stack (distinguished by icon/kind).
 *
 * Each source arrives on its own channel and replaces its own half; this holds
 * the latest of each and re-publishes the combined list on every update.
 */
object ConditionHudFeed {
    @Volatile
    private var hediffs: List<HediffEntry> = emptyList()

    @Volatile
    private var moodlets: List<HediffEntry> = emptyList()

    @Synchronized
    fun setHediffs(entries: List<HediffEntry>) {
        hediffs = entries
        republish()
    }

    @Synchronized
    fun setMoodlets(entries: List<HediffEntry>) {
        moodlets = entries
        republish()
    }

    private fun republish() {
        HediffHudState.replaceAll(hediffs + moodlets)
    }
}
