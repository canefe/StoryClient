package com.canefe.storyclient.client.combat

import net.minecraft.client.MinecraftClient
import org.lwjgl.glfw.GLFW
import kotlin.math.abs

/**
 * Polls GLFW each client tick for mouse button transitions and resolves
 * mouse-drag direction over a short capture window into a [SwingDir]
 * payload, then dispatches the appropriate combat intent.
 *
 * Works as a state machine:
 *   IDLE        → LMB press → CAPTURE_SWING (window N ticks)
 *   IDLE        → RMB press → CAPTURE_BLOCK (window N ticks)
 *   CAPTURE_*   → on window close, resolve delta, send intent, → IDLE
 *
 * SwingDir ordinals on wire (matches Story SwingDir.ordinal):
 *   0 OVERHEAD, 1 LEFT, 2 RIGHT, 3 THRUST
 */
object DirectionInputCapture {
    /** How many client ticks to accumulate mouse delta before resolving direction. */
    private const val CAPTURE_TICKS = 4

    /** Pixel threshold for a deliberate drag (arbitrary; tuned empirically). */
    private const val DRAG_THRESHOLD = 8.0

    private var lmbWasDown = false
    private var rmbWasDown = false

    private var captureMode: CaptureMode = CaptureMode.IDLE
    private var captureTicksLeft = 0
    private var dragXAccum = 0.0
    private var dragYAccum = 0.0
    private var lastCursorX = 0.0
    private var lastCursorY = 0.0

    /** Are we currently in a directional swing windup (so re-LMB sends DirectionSwitch)? */
    @Volatile var localInWindup: Boolean = false
    /** Are we busy (Windup/Active/Recovery/Stagger) — suppress fresh swing sends. */
    @Volatile var localBusy: Boolean = false

    /** When true, the HUD continuously displays the swing direction the next click would send. */
    @Volatile var liveAimEnabled: Boolean = false

    /** Rolling mouse-drag accumulator used to compute live aim independent of the click capture. */
    private var liveDragX = 0.0
    private var liveDragY = 0.0
    /** Latest resolved live-aim direction ordinal (0..3). */
    @Volatile var liveAimOrdinal: Int = 3

    fun tick() {
        val client = MinecraftClient.getInstance()
        // Don't capture if we don't have a world / are in a screen.
        if (client.world == null || client.currentScreen != null) {
            resetCapture()
            lmbWasDown = false
            rmbWasDown = false
            return
        }
        val handle = client.window.handle

        val lmb = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
        val rmb = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS

        // Cursor delta accumulation (only meaningful while a capture is open).
        val cx = DoubleArray(1); val cy = DoubleArray(1)
        GLFW.glfwGetCursorPos(handle, cx, cy)
        val dx = cx[0] - lastCursorX
        val dy = cy[0] - lastCursorY
        if (captureMode != CaptureMode.IDLE) {
            dragXAccum += dx
            dragYAccum += dy
        }
        // Live-aim: only re-resolve the ordinal when new motion clears the
        // direction threshold. Otherwise the live ordinal sticks until the
        // player deliberately drags somewhere new (Bannerlord/Mordhau feel).
        if (liveAimEnabled) {
            liveDragX += dx
            liveDragY += dy
            val absX = kotlin.math.abs(liveDragX)
            val absY = kotlin.math.abs(liveDragY)
            if (absX >= LIVE_COMMIT_THRESHOLD || absY >= LIVE_COMMIT_THRESHOLD) {
                liveAimOrdinal = resolveDirOrdinal(liveDragX, liveDragY)
                liveDragX = 0.0
                liveDragY = 0.0
            }
        } else {
            liveDragX = 0.0
            liveDragY = 0.0
        }
        lastCursorX = cx[0]; lastCursorY = cy[0]

        // Edge: LMB press
        if (lmb && !lmbWasDown) {
            if (liveAimEnabled) {
                // Bannerlord-style: the live ordinal is the commitment. Skip the
                // capture window entirely and dispatch immediately.
                dispatchImmediate(
                    if (localInWindup) CaptureMode.CAPTURE_DIRSWITCH else CaptureMode.CAPTURE_SWING,
                    liveAimOrdinal,
                )
            } else if (localInWindup) {
                openCapture(CaptureMode.CAPTURE_DIRSWITCH)
            } else {
                openCapture(CaptureMode.CAPTURE_SWING)
            }
        }
        // Edge: RMB press
        if (rmb && !rmbWasDown) {
            if (liveAimEnabled) {
                dispatchImmediate(CaptureMode.CAPTURE_BLOCK, liveAimOrdinal)
            } else {
                openCapture(CaptureMode.CAPTURE_BLOCK)
            }
        }
        // Edge: RMB release while blocking → end block
        if (!rmb && rmbWasDown) {
            // Send a "release" block intent with arbitrary dir; server only checks pressed=false.
            BlockIntentPayload.send(0, pressed = false)
        }

        lmbWasDown = lmb
        rmbWasDown = rmb

        // Tick the capture window.
        if (captureMode != CaptureMode.IDLE) {
            captureTicksLeft--
            if (captureTicksLeft <= 0) closeCaptureAndDispatch()
        }
    }

    private fun openCapture(mode: CaptureMode) {
        captureMode = mode
        captureTicksLeft = CAPTURE_TICKS
        dragXAccum = 0.0
        dragYAccum = 0.0
    }

    private fun resetCapture() {
        captureMode = CaptureMode.IDLE
        captureTicksLeft = 0
        dragXAccum = 0.0
        dragYAccum = 0.0
    }

    private fun dispatchImmediate(mode: CaptureMode, dirOrd: Int) {
        when (mode) {
            CaptureMode.CAPTURE_SWING -> {
                SwingIntentPayload.send(dirOrd)
                localInWindup = true
            }
            CaptureMode.CAPTURE_BLOCK -> BlockIntentPayload.send(dirOrd, pressed = true)
            CaptureMode.CAPTURE_DIRSWITCH -> DirectionSwitchPayload.send(dirOrd)
            CaptureMode.IDLE -> Unit
        }
        resetCapture()
    }

    private fun closeCaptureAndDispatch() {
        val dirOrd = resolveDirOrdinal(dragXAccum, dragYAccum)
        when (captureMode) {
            CaptureMode.CAPTURE_SWING -> {
                SwingIntentPayload.send(dirOrd)
                localInWindup = true
            }
            CaptureMode.CAPTURE_BLOCK -> BlockIntentPayload.send(dirOrd, pressed = true)
            CaptureMode.CAPTURE_DIRSWITCH -> DirectionSwitchPayload.send(dirOrd)
            CaptureMode.IDLE -> Unit
        }
        resetCapture()
    }

    /** Returns SwingDir ordinal: 0 OVERHEAD, 1 LEFT, 2 RIGHT, 3 THRUST. */
    private fun resolveDirOrdinal(dx: Double, dy: Double): Int {
        val absX = abs(dx); val absY = abs(dy)
        if (absX < DRAG_THRESHOLD && absY < DRAG_THRESHOLD) return 3 // THRUST
        return if (absY > absX) {
            if (dy < 0) 0 else 3 // OVERHEAD or THRUST (no DOWN dir; map down to thrust)
        } else {
            if (dx < 0) 1 else 2 // LEFT or RIGHT
        }
    }

    private enum class CaptureMode { IDLE, CAPTURE_SWING, CAPTURE_BLOCK, CAPTURE_DIRSWITCH }

    private const val LIVE_DECAY = 0.85

    /** Pixel delta required before the live-aim direction snaps to a new ordinal. */
    private const val LIVE_COMMIT_THRESHOLD = 40.0
}
