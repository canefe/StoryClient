package com.canefe.storyclient.client.cinematic

import com.canefe.storyclient.client.character.SelfCharacterState
import com.canefe.storyclient.client.ui.UIMessages
import net.minecraft.client.MinecraftClient
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d

/**
 * GTA-V-style spawn cinematic: the camera holds directly overhead (true
 * birds-eye, looking straight down) and snaps closer to the body in a few
 * discrete steps. Each snap is a short EASED move (snappy, not a glide) and is
 * punctuated by a faint white glow flash so the cut reads as deliberate rather
 * than jarring. Between snaps the camera never sits still — like a hand-held
 * cameraman it keeps creeping slowly toward the body and sways gently (see
 * [swayPos] / [swayAngle]). After the last snap it settles for a beat then eases
 * down into first-person ("we move into our perspective").
 *
 * Timeline (driven by wall-clock from [start]):
 *   • For each entry in [STEP_HEIGHTS]: a [STEP_EASE_MS] eased descent into the
 *     step height, then a [STEP_HOLD_MS] "living" hold that slowly creeps toward
 *     the NEXT height (a continuous drift, never frozen). A glow flash fires at
 *     the start of every step (see [glowAlpha]).
 *   • A [FINAL_HOLD_MS] settle beat at the lowest snap (sway alive, no creep).
 *   • One [FINAL_RESOLVE_MS] resolve phase — from the lowest snap height, ease
 *     position down to the player's eye and rotate pitch 90° → the player's look
 *     pitch. Sway amplitude ramps to zero across this phase so the landing is
 *     clean.
 *
 * The camera is decoupled from the entity: the player stays grounded the whole
 * time (the camera mixin forces third-person so the body renders). Input is
 * locked while [isActive] (see `PlayerFreezeMixin` / `MouseMixin`).
 *
 * Trigger: [start] is called when the world finishes loading on a join / world
 * change (from `BlackFadeTerrainScreen.close`).
 */
object SpawnCinematicController {

    /**
     * Camera heights (blocks above the body) for each birds-eye snap, highest
     * first. Each is visibly closer than the last. The resolve phase begins from
     * the final entry. The first entry is also the camera's starting height.
     */
    private val STEP_HEIGHTS = doubleArrayOf(28.0, 8.0)

    /**
     * Fraction (0..1) of the way toward the NEXT snap height that the camera
     * creeps during a step's hold, so it's always slowly closing in rather than
     * frozen. The final snap creeps this fraction toward the eye (height 0).
     */
    private const val CREEP_FRACTION = 0.18

    /**
     * Camera look-down angle during the snaps, in degrees from horizontal. Less
     * than 90° so it's a tilted high-angle shot (we see the character's face),
     * not a flat top-down on the scalp. The camera sits IN FRONT of the player
     * (along their facing) and looks back down at them.
     */
    private const val SNAP_PITCH = 58.0f

    /** Short eased descent into each step (the "snap", but smoothed). */
    private const val STEP_EASE_MS = 420L

    /** "Living" birds-eye hold after easing into a step (creep + sway, not frozen). */
    private const val STEP_HOLD_MS = 520L

    /** One full step = ease-in + hold. */
    private const val STEP_MS = STEP_EASE_MS + STEP_HOLD_MS

    /**
     * Settle beat at the lowest snap before the final descent — its OWN delay,
     * separate from the per-step [STEP_HOLD_MS]. Sway stays alive here; no creep.
     */
    private const val FINAL_HOLD_MS = 1200L

    /**
     * Duration of the final smooth drop from the lowest snap into first-person —
     * its OWN delay, separate from the per-step snap timing.
     */
    private const val FINAL_RESOLVE_MS = 700L

    /** How long each white glow flash takes to fade out, in ms. */
    private const val GLOW_MS = 260f

    /** Peak opacity (0..1) of the glow flash. Faint. */
    private const val GLOW_PEAK = 0.35f

    /** Hand-held sway: peak position wobble (blocks) and look jitter (degrees). */
    private const val SWAY_POS_AMP = 0.35
    private const val SWAY_ANGLE_AMP = 0.6f

    private val SNAP_TOTAL_MS = STEP_HEIGHTS.size * STEP_MS
    private val TOTAL_MS = SNAP_TOTAL_MS + FINAL_HOLD_MS + FINAL_RESOLVE_MS

    @Volatile
    private var startMs: Long? = null

    /** Volume of the ender-dragon-flap stinger played at each snap. */
    private const val SNAP_SOUND_VOLUME = 0.7f

    /** Saved `hudHidden` value to restore when the cinematic ends. */
    @Volatile
    private var savedHudHidden = false

    /** Last snap step index whose stinger has played, or -1 when none yet. */
    @Volatile
    private var lastSoundedStep = -1

    val isActive: Boolean
        get() = startMs != null

    /**
     * GTA-style post-process filter strength (0..1) for the current frame: full
     * through the snaps and final hold, then ramps to zero across the resolve so
     * the filter dissolves cleanly as we land in first-person. Zero when inactive.
     */
    fun filterStrength(): Float {
        val dt = elapsed() ?: return 0f
        return swayDampen(dt).toFloat()
    }

    fun start() {
        // Hide the HUD (crosshair, hotbar, etc.) like pressing F1 for the duration.
        // The hand is already hidden via the forced third-person camera.
        val options = MinecraftClient.getInstance().options
        if (!isActive) savedHudHidden = options.hudHidden
        options.hudHidden = true
        lastSoundedStep = -1
        startMs = System.currentTimeMillis()

        // Announce the player's Story character with a title, timed to the swoop.
        // Skipped until the server has pushed the name (story:char_self).
        val charName = SelfCharacterState.name
        println("[SpawnCinematic] start(): SelfCharacterState.name='$charName' id='${SelfCharacterState.characterId}' -> ${if (charName.isNotBlank()) "sending title" else "SKIP (name blank)"}")
        if (charName.isNotBlank()) {
            UIMessages.sendTitle(charName)
        }
    }

    fun cancel() {
        end()
    }

    /** Single end transition: clear state and restore the HUD. */
    private fun end() {
        startMs = null
        MinecraftClient.getInstance().options.hudHidden = savedHudHidden
    }

    /** Milliseconds elapsed since [start]; null when inactive. Clears at the end. */
    private fun elapsed(): Long? {
        val s = startMs ?: return null
        val dt = System.currentTimeMillis() - s
        if (dt >= TOTAL_MS) {
            end()
            return null
        }
        return dt
    }

    /** Smoothstep ease-in-out. */
    private fun ease(t: Float): Float {
        val c = MathHelper.clamp(t, 0f, 1f)
        return c * c * (3.0f - 2.0f * c)
    }

    /**
     * Camera world position for the current frame, or null if over / no player.
     * During a step the camera eases from the previous height into the current
     * step height then slowly creeps toward the next; during the final hold it
     * settles at the lowest snap; during resolve it eases down to the eye. A
     * hand-held sway is layered on top (faded out across the resolve).
     */
    fun cameraPos(tickDelta: Float): Vec3d? {
        val dt = elapsed() ?: return null
        val player = MinecraftClient.getInstance().player ?: return null
        val eye = playerEye(player, tickDelta)

        // Player facing as a horizontal unit vector (the camera sits along +this,
        // in front of the player, so it looks back at the face).
        val yawRad = Math.toRadians(player.yaw.toDouble())
        val fx = -Math.sin(yawRad)
        val fz = Math.cos(yawRad)
        // Horizontal distance to keep a constant SNAP_PITCH look-down for height h.
        val tan = Math.tan(Math.toRadians(SNAP_PITCH.toDouble()))

        val height: Double
        if (dt < SNAP_TOTAL_MS) {
            val step = (dt / STEP_MS).toInt().coerceIn(0, STEP_HEIGHTS.size - 1)
            val into = dt - step * STEP_MS
            val target = STEP_HEIGHTS[step]
            val from = if (step == 0) STEP_HEIGHTS[0] else STEP_HEIGHTS[step - 1]
            // The post-snap creep drifts toward the next snap. The LAST snap stays
            // put — its descent is owned by the dedicated final hold + resolve, so
            // creeping it toward the eye here would just pop back up at the hold.
            val next = if (step < STEP_HEIGHTS.size - 1) STEP_HEIGHTS[step + 1] else target
            height = if (into < STEP_EASE_MS) {
                MathHelper.lerp(ease(into.toFloat() / STEP_EASE_MS).toDouble(), from, target)
            } else {
                // Living hold: creep a fraction of the way toward the next height.
                val holdT = (into - STEP_EASE_MS).toFloat() / STEP_HOLD_MS
                MathHelper.lerp((ease(holdT) * CREEP_FRACTION).toDouble(), target, next)
            }
        } else if (dt < SNAP_TOTAL_MS + FINAL_HOLD_MS) {
            // Settle beat: hold at the lowest snap (sway still alive below).
            height = STEP_HEIGHTS.last()
        } else {
            // Resolve phase: ease the angled high shot down into the first-person
            // eye — height and forward offset collapse to zero.
            val e = ease((dt - SNAP_TOTAL_MS - FINAL_HOLD_MS).toFloat() / FINAL_RESOLVE_MS)
            height = MathHelper.lerp(e.toDouble(), STEP_HEIGHTS.last(), 0.0)
        }

        val fwd = height / tan
        val sway = swayPos(dt)
        return Vec3d(eye.x + fx * fwd + sway.x, eye.y + height + sway.y, eye.z + fz * fwd + sway.z)
    }

    /**
     * Camera yaw: points back at the player (their yaw + 180°) through the snaps
     * and final hold so the lens faces the character's front, then eases to the
     * player's own yaw on landing (normal first-person). A hand-held sway is
     * layered on, faded out across the resolve.
     */
    fun cameraYaw(): Float? {
        val dt = elapsed() ?: return null
        val player = MinecraftClient.getInstance().player ?: return null
        val swayYaw = swayAngle(dt).first
        if (dt < SNAP_TOTAL_MS + FINAL_HOLD_MS) return player.yaw + 180.0f + swayYaw
        val e = ease((dt - SNAP_TOTAL_MS - FINAL_HOLD_MS).toFloat() / FINAL_RESOLVE_MS)
        // Lerp +180° → 0° offset so the view swings around to the player's facing.
        return player.yaw + MathHelper.lerp(e, 180.0f, 0.0f) + swayYaw
    }

    /**
     * Camera pitch: a tilted high-angle [SNAP_PITCH] (looking down at the face)
     * through the snaps and final hold, then eases to the player's look pitch on
     * landing. A hand-held sway is layered on, faded out across the resolve.
     */
    fun cameraPitch(): Float? {
        val dt = elapsed() ?: return null
        val player = MinecraftClient.getInstance().player ?: return null
        val swayPitch = swayAngle(dt).second
        if (dt < SNAP_TOTAL_MS + FINAL_HOLD_MS) return SNAP_PITCH + swayPitch
        val e = ease((dt - SNAP_TOTAL_MS - FINAL_HOLD_MS).toFloat() / FINAL_RESOLVE_MS)
        return MathHelper.lerp(e, SNAP_PITCH, player.pitch) + swayPitch
    }

    /**
     * Current white-glow flash opacity (0..1) for the HUD overlay, peaking at the
     * start of each snap step and fading over [GLOW_MS]. Zero outside a flash and
     * when inactive.
     */
    fun glowAlpha(): Float {
        val dt = elapsed() ?: return 0f
        if (dt >= SNAP_TOTAL_MS) return 0f
        val into = (dt % STEP_MS).toFloat()
        if (into >= GLOW_MS) return 0f
        // Linear fade from peak → 0 across the flash.
        return GLOW_PEAK * (1f - into / GLOW_MS)
    }

    /**
     * Plays the ender-dragon-flap stinger once at the start of each snap step. Called
     * every frame (from the glow overlay, which already runs during the
     * cinematic); edge-detects step changes via [lastSoundedStep] so the sound
     * fires exactly once per step, never during the resolve phase.
     */
    fun tickStepSounds() {
        val dt = elapsed() ?: run { lastSoundedStep = -1; return }
        if (dt >= SNAP_TOTAL_MS) return
        val step = (dt / STEP_MS).toInt().coerceIn(0, STEP_HEIGHTS.size - 1)
        if (step == lastSoundedStep) return
        lastSoundedStep = step
        MinecraftClient.getInstance().soundManager.play(
            PositionedSoundInstance.master(SoundEvents.ENTITY_ENDER_DRAGON_FLAP, 1.0f, SNAP_SOUND_VOLUME),
        )
    }

    private fun playerEye(player: PlayerEntity, tickDelta: Float): Vec3d {
        val x = MathHelper.lerp(tickDelta.toDouble(), player.prevX, player.x)
        val y = MathHelper.lerp(tickDelta.toDouble(), player.prevY, player.y) + player.standingEyeHeight
        val z = MathHelper.lerp(tickDelta.toDouble(), player.prevZ, player.z)
        return Vec3d(x, y, z)
    }

    /**
     * Hand-held sway amplitude scale (0..1) for elapsed [dt]: full through the
     * snaps and final hold, then ramps to zero across the resolve so first-person
     * lands rock-steady.
     */
    private fun swayDampen(dt: Long): Double {
        val settled = SNAP_TOTAL_MS + FINAL_HOLD_MS
        if (dt < settled) return 1.0
        val e = ease((dt - settled).toFloat() / FINAL_RESOLVE_MS)
        return (1.0f - e).toDouble()
    }

    /**
     * Hand-held position wobble (a sub-block XYZ drift), driven by summed sines at
     * incommensurate frequencies so it never visibly loops. Deterministic in [dt].
     */
    private fun swayPos(dt: Long): Vec3d {
        val t = dt / 1000.0
        val a = SWAY_POS_AMP * swayDampen(dt)
        val x = (Math.sin(t * 1.7) + 0.5 * Math.sin(t * 3.1)) * a
        val y = (Math.sin(t * 1.3 + 1.0) + 0.5 * Math.sin(t * 2.3)) * a * 0.6
        val z = (Math.sin(t * 2.1 + 2.0) + 0.5 * Math.sin(t * 3.7)) * a
        return Vec3d(x, y, z)
    }

    /**
     * Hand-held look jitter (yaw, pitch) in degrees — a fraction of a degree so
     * the aim breathes without reading as shaky. Deterministic in [dt].
     */
    private fun swayAngle(dt: Long): Pair<Float, Float> {
        val t = dt / 1000.0
        val a = (SWAY_ANGLE_AMP * swayDampen(dt)).toFloat()
        val yaw = ((Math.sin(t * 1.9) + 0.5 * Math.sin(t * 3.3)).toFloat()) * a
        val pitch = ((Math.sin(t * 1.5 + 0.7) + 0.5 * Math.sin(t * 2.7)).toFloat()) * a * 0.7f
        return yaw to pitch
    }
}
