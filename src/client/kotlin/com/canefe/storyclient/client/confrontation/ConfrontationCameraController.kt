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

    /** Close-up: how far in front of the face the lens sits (blocks). A MC head
     * cube is ~0.5 block, so anything under ~2.5 puts the lens inside the model. */
    private const val CLOSEUP_DISTANCE = 3.0

    /** Two-shot: lateral offset from the midpoint (blocks). */
    private const val TWO_SHOT_SIDE = 4.0

    /** Two-shot: how far back from the midpoint (blocks). */
    private const val TWO_SHOT_BACK = 4.5

    private const val SWAY_POS_AMP = 0.18
    private const val SWAY_ANGLE_AMP = 0.35f

    @Volatile
    private var active = false

    @Volatile
    private var startMs = 0L
    private var lastCutMs = 0L

    /** 0 = close-up on A, 1 = two-shot, 2 = close-up on B. Cycles. */
    private var shot = 0

    /** Saved hudHidden to restore on exit (mirrors OOC / spawn cinematic). */
    private var savedHudHidden = false

    val isActive: Boolean
        get() = active

    fun start() {
        active = true
        startMs = System.currentTimeMillis()
        lastCutMs = startMs
        shot = 0
        // Hide the vanilla HUD (hearts, hotbar, hunger, crosshair, hands) for a
        // clean cinematic shot. HudRenderCallback still fires while hidden, so the
        // confrontation overlay keeps drawing. Restored in stop().
        val options = MinecraftClient.getInstance().options
        savedHudHidden = options.hudHidden
        options.hudHidden = true
    }

    fun stop() {
        active = false
        MinecraftClient.getInstance().options.hudHidden = savedHudHidden
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

    /** Radius (blocks) within which a non-focused entity blocks the shot. Set a
     * hair under the close-up distance so anything nearer the lens than the
     * framed subject gets culled, but the subject itself never does. */
    private const val CULL_RADIUS = 2.6

    /**
     * True when [entity] should be culled this frame: the scene is active, the
     * entity is NOT the current shot's subject, and it is within [CULL_RADIUS] of
     * the camera (so it would occlude the framed character). Consulted by the
     * entity-render mixin.
     */
    fun shouldCull(entity: Entity, cameraPos: Vec3d): Boolean {
        if (!active) return false
        if (isShotSubject(entity)) return false
        return entity.squaredDistanceTo(cameraPos) <= CULL_RADIUS * CULL_RADIUS
    }

    /** Whether [entity] is a subject of the current shot (stays visible). */
    private fun isShotSubject(entity: Entity): Boolean {
        val (activeE, otherE) = subjectEntities() ?: return false
        return when (shot) {
            1 -> entity == activeE || entity == otherE // two-shot: both framed
            0 -> entity == activeE
            else -> entity == otherE
        }
    }

    /** Resolve the two shot subjects as entities (active + other/target). */
    private fun subjectEntities(): Pair<Entity?, Entity?>? {
        val client = MinecraftClient.getInstance()
        val activeE = resolve(ConfrontationState.activeCharacterId) ?: client.player
        val otherE = resolve(ConfrontationState.targetCharacterId)
            ?: activeE?.let { otherRosterEntity(it) }
        return activeE to otherE
    }

    // ── transform contract (always active while the scene is) ────────────────

    fun cameraPos(tickDelta: Float): Vec3d? {
        if (!active) return null
        val sway = swayPos()
        if (shot == 1) {
            // Two-shot: off the perpendicular of the a→b line, backed off.
            val (a, b) = ends() ?: return null
            val mid = midpoint(a, b)
            val (px, pz) = perp(a, b)
            return Vec3d(
                mid.x + px * TWO_SHOT_SIDE + pz * -TWO_SHOT_BACK + sway.x,
                mid.y + 0.4 + sway.y,
                mid.z + pz * TWO_SHOT_SIDE + px * TWO_SHOT_BACK + sway.z,
            )
        }
        // Close-up — AFKcamera style. Stand at the subject's eye, take the
        // direction the SUBJECT is facing (their own yaw = where their face
        // points), step out along it, and look back at the face. This is the
        // afkcam mirror-and-move-forward trick: it never lands behind the model
        // regardless of where the other participant stands.
        val subj = closeUpSubject() ?: return null
        val eye = subj.getCameraPosVec(tickDelta)
        val yawRad = Math.toRadians(subj.getYaw(tickDelta).toDouble())
        // MC facing: forward = (-sin yaw, 0, cos yaw). The camera goes OUT along
        // the subject's facing (in front of their face) with a small side kick.
        val fx = -Math.sin(yawRad)
        val fz = Math.cos(yawRad)
        val side = if (shot == 0) 1.0 else -1.0
        val px = fz * side // perpendicular to facing (horizontal)
        val pz = -fx * side
        return Vec3d(
            eye.x + fx * CLOSEUP_DISTANCE + px * (CLOSEUP_DISTANCE * 0.35) + sway.x,
            eye.y + 0.25 + sway.y,
            eye.z + fz * CLOSEUP_DISTANCE + pz * (CLOSEUP_DISTANCE * 0.35) + sway.z,
        )
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
        if (shot == 1) {
            val (a, b) = ends() ?: return null
            return midpoint(a, b)
        }
        // Close-up: look at the subject's face (eye position).
        val subj = closeUpSubject() ?: return null
        return eye(subj)
    }

    /** The entity framed by the current close-up shot (0 = active, 2 = other). */
    private fun closeUpSubject(): Entity? {
        val client = MinecraftClient.getInstance()
        val activeE = resolve(ConfrontationState.activeCharacterId) ?: client.player
        val otherE = resolve(ConfrontationState.targetCharacterId)
            ?: activeE?.let { otherRosterEntity(it) }
            ?: client.player
        return if (shot == 0) activeE else otherE
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
