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

    /** Convenience snapshot from live HUD state. */
    fun snapshot(): List<HediffEntry> = HediffHudState.active
}
