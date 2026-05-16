package com.canefe.storyclient.client.permission

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the queue of active permission toasts for the HUD to render.
 *
 * Toasts arrive via [push] from [PermissionPacketReceiver]; the HUD reads
 * [active] each frame and animates accordingly. On Y/N keypress or timeout
 * the toast is removed via [dismiss].
 *
 * Single-active design: we show one toast at a time and queue the rest.
 * Multiple simultaneous gates are unusual (each Ask blocks story-go's
 * per-call goroutine) but possible if two pipelines fire close together.
 */
object PermissionToastState {

    data class Toast(
        val prompt: PermissionPacketReceiver.PromptDTO,
        /** Wall-clock ms when this toast should auto-dismiss as a deny. */
        val deadlineMs: Long,
        /** Wall-clock ms when this toast was pushed (for slide-in animation). */
        val shownAtMs: Long,
        /** Wall-clock ms when slide-out should begin (set when dismissed). */
        @Volatile var dismissingAtMs: Long? = null,
    )

    /** Toasts waiting after the active one (FIFO). */
    private val queue = ConcurrentLinkedDeque<Toast>()
    private val activeRef = AtomicReference<Toast?>(null)

    /** Currently visible toast, or null. */
    val active: Toast? get() = activeRef.get()

    /** Returns a snapshot of pending toasts (for HUD stacking, if desired). */
    fun pending(): List<Toast> = queue.toList()

    /** Called from packet receiver when story-go asks for permission. */
    fun push(prompt: PermissionPacketReceiver.PromptDTO) {
        val now = System.currentTimeMillis()
        val timeout = prompt.timeoutSec.coerceAtLeast(1)
        val toast = Toast(
            prompt = prompt,
            deadlineMs = now + timeout * 1000L,
            shownAtMs = now,
        )
        if (!activeRef.compareAndSet(null, toast)) {
            queue.addLast(toast)
        }
    }

    /**
     * Dismiss the active toast (Y/N pressed or timeout reached). Promotes
     * the next queued toast if any. Does NOT send the network response —
     * that's the caller's job (so the timeout path can dismiss without
     * sending anything).
     */
    fun dismiss(requestId: String) {
        val current = activeRef.get() ?: return
        if (current.prompt.requestId != requestId) return
        // Mark for slide-out; HUD removes once animation completes.
        current.dismissingAtMs = System.currentTimeMillis()
        // Promote next immediately so a new toast can start sliding in.
        val next = queue.pollFirst()
        if (next != null) {
            activeRef.set(next.copy(shownAtMs = System.currentTimeMillis()))
        } else {
            activeRef.set(null)
        }
    }

    /** Drop everything. Used on disconnect. */
    fun clear() {
        activeRef.set(null)
        queue.clear()
    }
}
