package com.canefe.storyclient.client.tips

/** Which Immersive Messages preset a tip renders with. */
enum class TipStyle {
    /** Top-left, achievement-style, unobtrusive. */
    TOAST,

    /** Centered above the hotbar, more intrusive. */
    POPUP,
}

/**
 * One one-time tip. Shown at most once ever (tracked by [id]) unless the player
 * resets tip progress. Authored in [Tips]; fired by id via
 * [TipManager.show].
 */
data class Tip(
    val id: String,
    val title: String,
    val subtitle: String,
    val style: TipStyle,
    /** On-screen duration in seconds (includes fade in/out). */
    val durationSecs: Float = 10f,
)
