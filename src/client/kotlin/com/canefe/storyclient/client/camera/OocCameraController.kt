package com.canefe.storyclient.client.camera

import com.canefe.storyclient.client.cinematic.SpawnCinematicController
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import org.lwjgl.glfw.GLFW

/**
 * Out-of-character (OOC) spectator camera: a client-only orbit around the local
 * player's own body ("stepping out of yourself to watch the scene"). The body
 * stays grounded where it is; only the camera detaches, held in third-person so
 * the model renders.
 *
 * This mirrors [SpawnCinematicController]'s camera-transform contract
 * ([cameraPos] / [cameraYaw] / [cameraPitch], consumed by the camera mixin at
 * the tail of `Camera#update`) but replaces the scripted timeline with live
 * mouse control: mouse deltas drive orbit [azimuth]/[elevation], scroll drives
 * [distance]. Movement input is frozen via `PlayerFreezeMixin` and look deltas
 * are routed here via `MouseMixin` while [isActive].
 *
 * v1 is entered only by the debug keybind ([DEFAULT_KEY], `O`). The eventual
 * production trigger (a server/director push) can call [setActive]; the toggle
 * is written as a plain public entry point so that drops in without refactoring.
 */
object OocCameraController {

    private const val CATEGORY = "key.categories.storyclient"
    private const val TOGGLE_KEY = "key.storyclient.ooc.toggle"

    /** Default toggle key. `O` is free (F/R/V/Y/J/H/B/G/K are taken). */
    private const val DEFAULT_KEY = GLFW.GLFW_KEY_O

    /** Orbit distance bounds (blocks from the body). Scroll clamps into this. */
    private const val MIN_DISTANCE = 2.0
    private const val MAX_DISTANCE = 12.0
    private const val DEFAULT_DISTANCE = 5.0

    /** Elevation clamp (degrees) — stay short of the poles to avoid gimbal flip. */
    private const val MIN_ELEVATION = -80.0
    private const val MAX_ELEVATION = 80.0

    /** Mouse look sensitivity (degrees per raw cursor-delta unit). */
    private const val LOOK_SENSITIVITY = 0.15

    /** Blocks of distance change per scroll notch. */
    private const val ZOOM_STEP = 1.0

    /** Hand-held sway: peak position wobble (blocks) and look jitter (degrees). */
    private const val SWAY_POS_AMP = 0.35
    private const val SWAY_ANGLE_AMP = 0.6f

    @Volatile
    private var active = false

    /** Wall-clock ms at activation; the deterministic base for sway. */
    @Volatile
    private var startMs = 0L

    /** Saved `hudHidden` to restore when OOC ends (mirrors the spawn cinematic). */
    private var savedHudHidden = false

    /** Orbit azimuth (degrees around the body, world yaw of the camera→body ray). */
    private var azimuth = 0.0

    /** Orbit elevation (degrees above the horizon). */
    private var elevation = 20.0

    /** Orbit radius (blocks). */
    private var distance = DEFAULT_DISTANCE

    private lateinit var toggle: KeyBinding

    val isActive: Boolean
        get() = active

    fun register() {
        toggle = KeyBindingHelper.registerKeyBinding(
            KeyBinding(TOGGLE_KEY, InputUtil.Type.KEYSYM, DEFAULT_KEY, CATEGORY),
        )
    }

    /**
     * Per-tick check. Drain `wasPressed()` so a held key fires once. Wire from
     * `ClientTickEvents.END_CLIENT_TICK`.
     */
    fun tick() {
        if (!::toggle.isInitialized) return
        val client = MinecraftClient.getInstance()
        // Don't toggle while typing in a screen; still drain the queue.
        if (client.currentScreen != null) {
            while (toggle.wasPressed()) {}
            return
        }
        var pressed = false
        while (toggle.wasPressed()) pressed = true
        if (pressed) setActive(!active)
    }

    /**
     * Enter/exit OOC. Public so a future server-push receiver can drive it. When
     * entering, seeds the orbit azimuth/elevation from the player's current facing
     * so the transition doesn't jump. Ignored while the spawn cinematic owns the
     * camera.
     */
    fun setActive(value: Boolean) {
        if (value == active) return
        val options = MinecraftClient.getInstance().options
        if (value) {
            if (SpawnCinematicController.isActive) return
            val player = MinecraftClient.getInstance().player ?: return
            // Seed azimuth = player yaw: the camera starts in front of the player,
            // angled back at their face (a natural "step out and look at yourself"
            // framing), and orbits from there.
            azimuth = player.yaw.toDouble()
            elevation = 20.0
            distance = DEFAULT_DISTANCE
            startMs = System.currentTimeMillis()
            // Hide the HUD (crosshair, hotbar, …) like the spawn cinematic — we've
            // stepped out of our body, so no gameplay HUD. Saved for restore.
            savedHudHidden = options.hudHidden
            options.hudHidden = true
            // First-ever OOC entry teaches the controls (once, then persisted).
            com.canefe.storyclient.client.tips.TipManager.show("ooc_camera")
        } else {
            options.hudHidden = savedHudHidden
        }
        active = value
    }

    /** Feed raw mouse cursor deltas into the orbit. Called from `MouseMixin`. */
    fun onMouseDelta(deltaX: Double, deltaY: Double) {
        azimuth = (azimuth + deltaX * LOOK_SENSITIVITY).mod(360.0)
        elevation = MathHelper.clamp(
            elevation - deltaY * LOOK_SENSITIVITY,
            MIN_ELEVATION,
            MAX_ELEVATION,
        )
    }

    /** Feed a scroll amount (notches, +up) into the orbit distance. */
    fun onScroll(amount: Double) {
        distance = MathHelper.clamp(distance - amount * ZOOM_STEP, MIN_DISTANCE, MAX_DISTANCE)
    }

    /**
     * Camera world position for the current frame, or null if inactive / no
     * player. Sits on a sphere of [distance] around the body's eye at
     * ([azimuth], [elevation]).
     */
    fun cameraPos(tickDelta: Float): Vec3d? {
        if (!active) return null
        val player = MinecraftClient.getInstance().player ?: return null
        val eye = playerEye(player, tickDelta)
        val az = Math.toRadians(azimuth)
        val el = Math.toRadians(elevation)
        val horiz = Math.cos(el) * distance
        // Camera→body ray points along (sin az, -sin el, -cos az) in MC's frame;
        // the camera sits opposite the body along that ray.
        val ox = Math.sin(az) * horiz
        val oy = Math.sin(el) * distance
        val oz = -Math.cos(az) * horiz
        val sway = swayPos()
        return Vec3d(eye.x - ox + sway.x, eye.y + oy + sway.y, eye.z - oz + sway.z)
    }

    /**
     * Camera yaw so the lens looks back at the body. The camera sits at
     * `eye - offset` where the horizontal offset is `(sin az, -cos az)·h`; the
     * look direction (camera→body) is therefore `(sin az, -cos az)`. Matching
     * MC's look basis `x = -sin(yaw)`, `z = cos(yaw)` gives `yaw = az + 180`.
     */
    fun cameraYaw(): Float? {
        if (!active) return null
        return (azimuth + 180.0).toFloat() + swayAngle().first
    }

    /**
     * Camera pitch so the lens looks back at the body. The look direction's
     * vertical component is `-sin(el)` (camera is above the body when el > 0);
     * MC pitch is `-sin(pitch)` and positive = looking down, so `pitch = el`.
     */
    fun cameraPitch(): Float? {
        if (!active) return null
        return elevation.toFloat() + swayAngle().second
    }

    private fun playerEye(player: PlayerEntity, tickDelta: Float): Vec3d {
        val x = MathHelper.lerp(tickDelta.toDouble(), player.prevX, player.x)
        val y = MathHelper.lerp(tickDelta.toDouble(), player.prevY, player.y) + player.standingEyeHeight
        val z = MathHelper.lerp(tickDelta.toDouble(), player.prevZ, player.z)
        return Vec3d(x, y, z)
    }

    /** Seconds since activation — the deterministic time base for sway. */
    private fun swayTime(): Double = (System.currentTimeMillis() - startMs) / 1000.0

    /**
     * Hand-held position wobble (a sub-block XYZ drift), driven by summed sines at
     * incommensurate frequencies so it never visibly loops. Ported from the spawn
     * cinematic's [SpawnCinematicController] hand-held feel; always at full
     * amplitude here (no resolve ramp).
     */
    private fun swayPos(): Vec3d {
        val t = swayTime()
        val a = SWAY_POS_AMP
        val x = (Math.sin(t * 1.7) + 0.5 * Math.sin(t * 3.1)) * a
        val y = (Math.sin(t * 1.3 + 1.0) + 0.5 * Math.sin(t * 2.3)) * a * 0.6
        val z = (Math.sin(t * 2.1 + 2.0) + 0.5 * Math.sin(t * 3.7)) * a
        return Vec3d(x, y, z)
    }

    /**
     * Hand-held look jitter (yaw, pitch) in degrees — a fraction of a degree so
     * the aim breathes without reading as shaky. Ported from the spawn cinematic.
     */
    private fun swayAngle(): Pair<Float, Float> {
        val t = swayTime()
        val a = SWAY_ANGLE_AMP
        val yaw = ((Math.sin(t * 1.9) + 0.5 * Math.sin(t * 3.3)).toFloat()) * a
        val pitch = ((Math.sin(t * 1.5 + 0.7) + 0.5 * Math.sin(t * 2.7)).toFloat()) * a * 0.7f
        return yaw to pitch
    }
}
