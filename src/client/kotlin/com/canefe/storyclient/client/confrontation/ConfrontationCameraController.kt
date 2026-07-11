package com.canefe.storyclient.client.confrontation

import net.minecraft.client.MinecraftClient
import net.minecraft.entity.Entity
import net.minecraft.util.math.Vec3d

/**
 * Camera director for a LOCKED confrontation. ALWAYS drives the camera transform
 * (position + look-at) — it never attaches the camera to an entity (that put the
 * lens inside a character's head). It cuts on a timer between:
 *  - a CLOSE-UP: camera floats in front of the active speaker's FACE, looking
 *    back at their eyes (like AFKcamera panning to your face); and
 *  - a WIDE TWO-SHOT: camera off the side of the midpoint, framing both
 *    participants.
 *
 * The transform is consumed by the camera mixin at the tail of `Camera#update`.
 * Hand-held sway is ported from the OOC / spawn cinematic controllers.
 */
object ConfrontationCameraController {

    /** Seconds a shot holds before cutting. */
    private const val SHOT_HOLD_SECONDS = 4.0

    /** Close-up: how far in front of the face the lens sits (blocks). */
    private const val CLOSEUP_DISTANCE = 1.6

    /** Two-shot: lateral offset from the midpoint (blocks). */
    private const val TWO_SHOT_SIDE = 3.2

    /** Two-shot: how far back from the midpoint (blocks). */
    private const val TWO_SHOT_BACK = 2.2

    private const val SWAY_POS_AMP = 0.18
    private const val SWAY_ANGLE_AMP = 0.35f

    @Volatile
    private var active = false

    @Volatile
    private var startMs = 0L
    private var lastCutMs = 0L

    /** 0 = close-up on A, 1 = two-shot, 2 = close-up on B. Cycles. */
    private var shot = 0

    val isActive: Boolean
        get() = active

    fun start() {
        active = true
        startMs = System.currentTimeMillis()
        lastCutMs = startMs
        shot = 0
    }

    fun stop() {
        active = false
    }

    /** Advance the shot cycle on the hold timer. */
    fun tick() {
        if (!active) return
        val now = System.currentTimeMillis()
        if ((now - lastCutMs) / 1000.0 >= SHOT_HOLD_SECONDS) {
            shot = (shot + 1) % 3
            lastCutMs = now
        }
    }

    /** Pure geometry: midpoint of two positions. */
    fun midpoint(a: Vec3d, b: Vec3d): Vec3d =
        Vec3d((a.x + b.x) / 2.0, (a.y + b.y) / 2.0, (a.z + b.z) / 2.0)

    // ── transform contract (always active while the scene is) ────────────────

    fun cameraPos(tickDelta: Float): Vec3d? {
        if (!active) return null
        val (a, b) = ends() ?: return null
        val sway = swayPos()
        return when (shot) {
            1 -> {
                // Two-shot: off the perpendicular of the a→b line, backed off.
                val mid = midpoint(a, b)
                val (px, pz) = perp(a, b)
                Vec3d(
                    mid.x + px * TWO_SHOT_SIDE + pz * -TWO_SHOT_BACK + sway.x,
                    mid.y + 0.4 + sway.y,
                    mid.z + pz * TWO_SHOT_SIDE + px * TWO_SHOT_BACK + sway.z,
                )
            }
            else -> {
                // Close-up: in FRONT of the speaker's face, offset toward the
                // other participant so we see their face 3/4 on.
                val (face, other) = if (shot == 0) a to b else b to a
                val dir = horizNorm(other.x - face.x, other.z - face.z)
                Vec3d(
                    face.x + dir.first * CLOSEUP_DISTANCE + sway.x,
                    face.y + sway.y,
                    face.z + dir.second * CLOSEUP_DISTANCE + sway.z,
                )
            }
        }
    }

    fun cameraYaw(): Float? {
        if (!active) return null
        val look = lookTarget() ?: return null
        val pos = cameraPos(1.0f) ?: return null
        val lx = look.x - pos.x
        val lz = look.z - pos.z
        val yaw = Math.toDegrees(Math.atan2(-lx, lz)).toFloat()
        return yaw + swayAngle().first
    }

    fun cameraPitch(): Float? {
        if (!active) return null
        val look = lookTarget() ?: return null
        val pos = cameraPos(1.0f) ?: return null
        val ly = look.y - pos.y
        val horiz = Math.hypot(look.x - pos.x, look.z - pos.z)
        val pitch = Math.toDegrees(-Math.atan2(ly, horiz)).toFloat()
        return pitch + swayAngle().second
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** The point the lens looks at this shot (a face, or the midpoint). */
    private fun lookTarget(): Vec3d? {
        val (a, b) = ends() ?: return null
        return when (shot) {
            1 -> midpoint(a, b)
            0 -> a
            else -> b
        }
    }

    /** Eye positions of the two framed participants (active + target/other). */
    private fun ends(): Pair<Vec3d, Vec3d>? {
        val client = MinecraftClient.getInstance()
        val activeE = resolve(ConfrontationState.activeCharacterId) ?: client.player ?: return null
        val otherE = resolve(ConfrontationState.targetCharacterId)
            ?: otherRosterEntity(activeE)
            ?: client.player
            ?: return null
        return eye(activeE) to eye(otherE)
    }

    private fun eye(e: Entity): Vec3d = e.pos.add(0.0, e.standingEyeHeight.toDouble(), 0.0)

    private fun resolve(id: String?): Entity? {
        if (id == null) return null
        val world = MinecraftClient.getInstance().world ?: return null
        return world.entities.firstOrNull { e ->
            e.uuidAsString == id ||
                e.name.string.equals(id, ignoreCase = true) ||
                e.customName?.string?.equals(id, ignoreCase = true) == true
        }
    }

    /** Fallback second subject: the nearest other entity to the active one. */
    private fun otherRosterEntity(active: Entity): Entity? {
        val world = MinecraftClient.getInstance().world ?: return null
        return world.entities
            .filter { it != active && it.standingEyeHeight > 0.5f }
            .minByOrNull { it.squaredDistanceTo(active) }
    }

    private fun perp(a: Vec3d, b: Vec3d): Pair<Double, Double> {
        val dx = b.x - a.x
        val dz = b.z - a.z
        val len = Math.hypot(dx, dz).takeIf { it > 1e-3 } ?: 1.0
        return -dz / len to dx / len
    }

    private fun horizNorm(dx: Double, dz: Double): Pair<Double, Double> {
        val len = Math.hypot(dx, dz).takeIf { it > 1e-3 } ?: 1.0
        return dx / len to dz / len
    }

    private fun swayTime(): Double = (System.currentTimeMillis() - startMs) / 1000.0

    private fun swayPos(): Vec3d {
        val t = swayTime()
        val a = SWAY_POS_AMP
        return Vec3d(
            (Math.sin(t * 1.7) + 0.5 * Math.sin(t * 3.1)) * a,
            (Math.sin(t * 1.3 + 1.0) + 0.5 * Math.sin(t * 2.3)) * a * 0.6,
            (Math.sin(t * 2.1 + 2.0) + 0.5 * Math.sin(t * 3.7)) * a,
        )
    }

    private fun swayAngle(): Pair<Float, Float> {
        val t = swayTime()
        val a = SWAY_ANGLE_AMP
        val yaw = ((Math.sin(t * 1.9) + 0.5 * Math.sin(t * 3.3)).toFloat()) * a
        val pitch = ((Math.sin(t * 1.5 + 0.7) + 0.5 * Math.sin(t * 2.7)).toFloat()) * a * 0.7f
        return yaw to pitch
    }
}
