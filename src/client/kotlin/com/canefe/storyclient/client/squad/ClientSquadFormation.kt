package com.canefe.storyclient.client.squad

import kotlin.math.cos
import kotlin.math.sin

/** Mirror of server-side [com.canefe.story.npc.squad.SquadFormation]. */
enum class ClientSquadFormation { LINE, WEDGE, COLUMN, LOOSE, SQUARE, CIRCLE, ECHELON }

/**
 * Client-side port of SquadFormationCalculator. Same math, same result —
 * needed so the preview renderer can show slot positions without a server
 * round-trip on every mouse move.
 *
 * Keep in lockstep with `npc/squad/SquadFormationCalculator.kt` server-side.
 */
object ClientFormationCalculator {
    private const val SPACING = 2.0

    /** Returns slot offsets relative to the anchor (x, y, z). */
    fun slotOffsets(
        facingYawDeg: Float,
        memberCount: Int,
        formation: ClientSquadFormation,
    ): List<Triple<Double, Double, Double>> {
        if (memberCount <= 0) return emptyList()
        val local = localOffsets(formation, memberCount)
        val yawRad = Math.toRadians(facingYawDeg.toDouble())
        val cos = cos(-yawRad)
        val sin = sin(-yawRad)
        return local.map { (lx, lz) ->
            val rx = lx * cos - lz * sin
            val rz = lx * sin + lz * cos
            Triple(rx, 0.0, rz)
        }
    }

    private fun localOffsets(formation: ClientSquadFormation, count: Int): List<Pair<Double, Double>> =
        when (formation) {
            ClientSquadFormation.LINE -> line(count)
            ClientSquadFormation.WEDGE -> wedge(count)
            ClientSquadFormation.COLUMN -> column(count)
            ClientSquadFormation.LOOSE -> loose(count)
            ClientSquadFormation.SQUARE -> square(count)
            ClientSquadFormation.CIRCLE -> circle(count)
            ClientSquadFormation.ECHELON -> echelon(count)
        }

    private fun line(count: Int): List<Pair<Double, Double>> {
        val centerOffset = (count - 1) / 2.0
        return (0 until count).map { i -> (i - centerOffset) * SPACING to 0.0 }
    }

    private fun wedge(count: Int): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        out.add(0.0 to 0.0)
        var depth = 1
        var i = 1
        while (i < count) {
            out.add(-depth * SPACING to -depth * SPACING)
            i++
            if (i >= count) break
            out.add(depth * SPACING to -depth * SPACING)
            i++
            depth++
        }
        return out
    }

    private fun column(count: Int): List<Pair<Double, Double>> =
        (0 until count).map { i -> 0.0 to -i * SPACING }

    private fun loose(count: Int): List<Pair<Double, Double>> =
        (0 until count).map { i ->
            val angle = Math.toRadians(i * 137.5)
            val radius = SPACING * Math.sqrt(i.toDouble())
            radius * cos(angle) to radius * sin(angle)
        }

    private fun square(count: Int): List<Pair<Double, Double>> {
        if (count <= 0) return emptyList()
        if (count == 1) return listOf(0.0 to 0.0)
        if (count < 4) return line(count)
        val perSide = (count + 3) / 4
        val sideHalf = (perSide - 1) / 2.0 * SPACING
        val out = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until perSide) {
            val rightOffset = (i - (perSide - 1) / 2.0) * SPACING
            out.add(rightOffset to sideHalf); if (out.size >= count) return out
        }
        for (i in 0 until perSide) {
            out.add(sideHalf to sideHalf - (i + 1) * SPACING); if (out.size >= count) return out
        }
        for (i in 0 until perSide) {
            out.add(sideHalf - (i + 1) * SPACING to -sideHalf); if (out.size >= count) return out
        }
        for (i in 0 until perSide) {
            out.add(-sideHalf to -sideHalf + (i + 1) * SPACING); if (out.size >= count) return out
        }
        return out
    }

    private fun circle(count: Int): List<Pair<Double, Double>> {
        if (count <= 0) return emptyList()
        if (count == 1) return listOf(0.0 to 0.0)
        val radius = (count * SPACING) / (2 * Math.PI)
        return (0 until count).map { i ->
            val angle = 2 * Math.PI * i / count
            (radius * sin(angle)) to (radius * cos(angle))
        }
    }

    private fun echelon(count: Int): List<Pair<Double, Double>> =
        (0 until count).map { i -> (i * SPACING) to (-i * SPACING) }
}

/** Map between the formation label string sent from server and the client enum. */
fun formationFromLabel(label: String): ClientSquadFormation =
    when (label.uppercase()) {
        "LINE" -> ClientSquadFormation.LINE
        "WEDGE" -> ClientSquadFormation.WEDGE
        "COLUMN" -> ClientSquadFormation.COLUMN
        "LOOSE" -> ClientSquadFormation.LOOSE
        "SQUARE" -> ClientSquadFormation.SQUARE
        "CIRCLE" -> ClientSquadFormation.CIRCLE
        "ECHELON" -> ClientSquadFormation.ECHELON
        else -> ClientSquadFormation.LINE
    }
