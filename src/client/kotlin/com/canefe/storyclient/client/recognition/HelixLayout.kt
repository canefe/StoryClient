package com.canefe.storyclient.client.recognition

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared per-frame layout state published by [HelixNametagRenderer] so other
 * world-anchored UI (sticky action pill, perception popups) can stack directly
 * beneath the Helix card with identical scale and horizontal alignment.
 *
 * The card is gated on the crosshair-targeted entity, so consumers reading this
 * state inherit the same gate "for free": stale entries (different uuid or older
 * than one frame) mean no card was drawn this frame → no follow-on UI either.
 */
object HelixLayout {
    /**
     * @param targetUuid    Entity UUID the Helix card was just drawn for.
     * @param cardBottomY   Post-scale Y of the card's bottom edge (descending Y in
     *                      Helix's coordinate system, since it scales by `-SCALE`).
     * @param cardRightX    Post-scale X of the card's right edge — `-SIDE_OFFSET_PX`
     *                      in Helix terms. Consumers should align their right edge
     *                      to this value so pills sit in the same column as the card.
     * @param scale         The world-units-per-post-scale-pixel factor Helix used
     *                      (currently 0.006f). Reused so pill geometry matches.
     * @param anchorMaxY    World Y of the entity's bounding box top (where Helix
     *                      anchors). Pills anchor at the same world point and
     *                      stack via post-scale pixel offsets.
     * @param frameMs       System time when the entry was written, used for
     *                      staleness checks by consumers.
     */
    data class State(
        val targetUuid: UUID,
        val cardBottomY: Float,
        val cardRightX: Float,
        val scale: Float,
        val anchorMaxY: Double,
        val frameMs: Long,
    )

    private val current = AtomicReference<State?>(null)

    fun publish(state: State) {
        current.set(state)
    }

    /** Returns the latest layout if it was written within [maxAgeMs] for [uuid], else null. */
    fun get(uuid: UUID, maxAgeMs: Long = 100L): State? {
        val s = current.get() ?: return null
        if (s.targetUuid != uuid) return null
        if (System.currentTimeMillis() - s.frameMs > maxAgeMs) return null
        return s
    }
}
