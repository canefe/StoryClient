package com.canefe.storyclient.client.health

import com.canefe.storyclient.client.hediff.HediffEntry
import com.canefe.storyclient.client.hediff.HediffHudState

/** Derived grouping of the local player's hediffs for the Health window. Pure; no packets. */
object HealthState {
    fun wholeBody(entries: List<HediffEntry>): List<HediffEntry> =
        entries.filter { it.bodyPart.isBlank() }

    fun byPart(entries: List<HediffEntry>): Map<String, List<HediffEntry>> =
        entries.filter { it.bodyPart.isNotBlank() }.groupByTo(LinkedHashMap()) { it.bodyPart }

    fun partSeverity(entries: List<HediffEntry>, part: String): Float =
        entries.filter { it.bodyPart == part }
            .maxOfOrNull { HediffHudState.severityFraction(it) } ?: 0f

    /**
     * Snapshot of the live condition state, filtered to real hediffs only.
     * [HediffHudState.active] is the UNIFIED right-edge HUD feed (hediffs +
     * moodlets), but the Health window's Conditions/Injuries are physical hediffs
     * only — moodlets (Quenched, Well Rested, …) belong on the moodlet HUD, not
     * here. Filter by kind so mood states don't leak into Conditions.
     */
    fun snapshot(): List<HediffEntry> = HediffHudState.active.filter { it.kind == "hediff" }
}
