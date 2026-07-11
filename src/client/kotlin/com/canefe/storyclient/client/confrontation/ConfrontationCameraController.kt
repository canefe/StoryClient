package com.canefe.storyclient.client.confrontation

import com.canefe.storyclient.client.decision.CinematicCameraController
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.Entity
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d

/**
 * Camera director for a LOCKED confrontation. Cuts between:
 *  - a CLOSE-UP on the active/speaking participant (reusing
 *    [CinematicCameraController.setCameraEntity]-style attachment), and
 *  - a WIDE TWO-SHOT framing both the active char and their target, via the same
 *    `cameraPos/cameraYaw/cameraPitch` transform contract the OOC camera uses
 *    (consumed by the camera mixin at the tail of `Camera#update`).
 *
 * The transform + hand-held sway are ported from
 * [com.canefe.storyclient.client.camera.OocCameraController].
 */
object ConfrontationCameraController {

    /** Seconds each shot holds before cutting to the other. */
    private const val SHOT_HOLD_SECONDS = 3.0

    /** Two-shot camera distance back from the framing midpoint (blocks). */
    private const val TWO_SHOT_DISTANCE = 4.0

    /** Hand-held sway amplitudes (ported from OocCameraController). */
    private const val SWAY_POS_AMP = 0.35
    private const val SWAY_ANGLE_AMP = 0.6f

    @Volatile
    private var active = false

    @Volatile
    private var startMs = 0L

    private var originalCameraEntity: Entity? = null

    /** true = wide two-shot (transform-driven); false = close-up (entity-attached). */
    private var twoShot = false
    private var lastCutMs = 0L

    val isActive: Boolean
        get() = active

    /** Whether the wide two-shot transform should drive the camera this frame. */
    val isTwoShot: Boolean
        get() = active && twoShot

    fun start() {
        val client = MinecraftClient.getInstance()
        originalCameraEntity = client.cameraEntity
        active = true
        startMs = System.currentTimeMillis()
        lastCutMs = startMs
        twoShot = false
    }

    fun stop() {
        val client = MinecraftClient.getInstance()
        originalCameraEntity?.let { client.setCameraEntity(it) }
        originalCameraEntity = null
        active = false
    }

    /** Called each client tick. Advances shot selection and sets the close-up target. */
    fun tick() {
        if (!active) return
        val now = System.currentTimeMillis()
        if ((now - lastCutMs) / 1000.0 >= SHOT_HOLD_SECONDS) {
            twoShot = !twoShot
            lastCutMs = now
        }
        val client = MinecraftClient.getInstance()
        if (twoShot) {
            // Transform-driven; keep camera on the player entity as the anchor so
            // the mixin's pos/rot override reads a stable base.
            client.player?.let { client.setCameraEntity(it) }
        } else {
            val target = resolveActive() ?: client.player ?: return
            client.setCameraEntity(target)
        }
    }

    /** Pure geometry: the midpoint of two participant positions. */
    fun midpoint(a: Vec3d, b: Vec3d): Vec3d =
        Vec3d((a.x + b.x) / 2.0, (a.y + b.y) / 2.0, (a.z + b.z) / 2.0)

    // ── OOC-style transform contract (only used in two-shot mode) ────────────

    fun cameraPos(tickDelta: Float): Vec3d? {
        if (!isTwoShot) return null
        val (a, b) = twoShotEnds() ?: return null
        val mid = midpoint(a, b)
        // Camera sits back along the axis perpendicular to the a→b line, on the
        // horizontal plane, looking at the midpoint. Perp of (dx,dz) is (-dz,dx).
        val dx = b.x - a.x
        val dz = b.z - a.z
        val len = Math.hypot(dx, dz).takeIf { it > 1e-3 } ?: 1.0
        val px = -dz / len
        val pz = dx / len
        val sway = swayPos()
        return Vec3d(
            mid.x + px * TWO_SHOT_DISTANCE + sway.x,
            mid.y + 1.4 + sway.y,
            mid.z + pz * TWO_SHOT_DISTANCE + sway.z,
        )
    }

    fun cameraYaw(): Float? {
        if (!isTwoShot) return null
        val (a, b) = twoShotEnds() ?: return null
        val mid = midpoint(a, b)
        val pos = cameraPos(1.0f) ?: return null
        val lx = mid.x - pos.x
        val lz = mid.z - pos.z
        // MC yaw: look dir x = -sin(yaw), z = cos(yaw) → yaw = atan2(-lx, lz).
        val yaw = Math.toDegrees(Math.atan2(-lx, lz)).toFloat()
        return yaw + swayAngle().first
    }

    fun cameraPitch(): Float? {
        if (!isTwoShot) return null
        val (a, b) = twoShotEnds() ?: return null
        val mid = midpoint(a, b)
        val pos = cameraPos(1.0f) ?: return null
        val ly = mid.y - pos.y
        val horiz = Math.hypot(mid.x - pos.x, mid.z - pos.z)
        val pitch = Math.toDegrees(-Math.atan2(ly, horiz)).toFloat()
        return pitch + swayAngle().second
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun resolveActive(): Entity? {
        val id = ConfrontationState.activeCharacterId ?: return null
        return entityByCharacterId(id)
    }

    private fun twoShotEnds(): Pair<Vec3d, Vec3d>? {
        val client = MinecraftClient.getInstance()
        val activeE = resolveActive() ?: client.player ?: return null
        val targetId = ConfrontationState.targetCharacterId
        val targetE = targetId?.let { entityByCharacterId(it) } ?: client.player ?: return null
        return activeE.pos.add(0.0, activeE.standingEyeHeight.toDouble(), 0.0) to
            targetE.pos.add(0.0, targetE.standingEyeHeight.toDouble(), 0.0)
    }

    /** Resolve an entity by character id — matches uuid or custom/display name. */
    private fun entityByCharacterId(id: String): Entity? {
        val world = MinecraftClient.getInstance().world ?: return null
        return world.entities.firstOrNull { e ->
            e.uuidAsString == id ||
                e.name.string.equals(id, ignoreCase = true) ||
                e.customName?.string?.equals(id, ignoreCase = true) == true
        }
    }

    private fun swayTime(): Double = (System.currentTimeMillis() - startMs) / 1000.0

    private fun swayPos(): Vec3d {
        val t = swayTime()
        val a = SWAY_POS_AMP
        val x = (Math.sin(t * 1.7) + 0.5 * Math.sin(t * 3.1)) * a
        val y = (Math.sin(t * 1.3 + 1.0) + 0.5 * Math.sin(t * 2.3)) * a * 0.6
        val z = (Math.sin(t * 2.1 + 2.0) + 0.5 * Math.sin(t * 3.7)) * a
        return Vec3d(x, y, z)
    }

    private fun swayAngle(): Pair<Float, Float> {
        val t = swayTime()
        val a = SWAY_ANGLE_AMP
        val yaw = ((Math.sin(t * 1.9) + 0.5 * Math.sin(t * 3.3)).toFloat()) * a
        val pitch = ((Math.sin(t * 1.5 + 0.7) + 0.5 * Math.sin(t * 2.7)).toFloat()) * a * 0.7f
        return yaw to pitch
    }
}
