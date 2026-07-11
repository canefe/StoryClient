# Out-of-Character (OOC) Spectator Camera — v1 Design

**Date:** 2026-07-08
**Repo:** StoryClient (Fabric 1.21.1, Kotlin, Yarn mappings)
**Status:** Approved design, ready for implementation plan

## Summary

A client-only toggle that detaches the camera into a mouse-driven orbit around
the local player's own body while the body stays put — "stepping out of
yourself to watch the scene." This is the *out-of-character* (OOC) counterpart
to normal in-character play (ordinary Minecraft first/third-person survival).

## Scope

**In scope (v1):**

- Client-only. No server packet, no game-mode change, no server round-trip.
- Camera orbits the local player entity: mouse drives azimuth/elevation, scroll
  drives orbit distance (zoom).
- Player body stays exactly where it is (frozen input) while OOC is active.
- Enter/exit via a **debug keybind** (`O`), toggling in and out of OOC.
- Third-person rendering is forced via the `Camera.thirdPerson` accessor so the
  player's own body renders while the camera is detached in the air.
- Fully reversible: toggling off snaps the camera back to the body and restores
  vanilla behavior with nothing to clean up.

**Explicitly out of scope (v1 — YAGNI):**

- Server-push trigger. The eventual production trigger is a server/director S2C
  message that forces OOC (e.g. during a cutscene). v1 does **not** wire this.
  The keybind is the only entry point. (No stub receiver is added either — the
  toggle is a plain public function the future receiver can call.)
- Camera–terrain collision / smart framing (camera may clip terrain — accepted).
- Body puppeting by the sim (the body just stands idle — decided as Q1=C).
- Any OOC HUD, label, or nametag. `SelfCharacterState.name` is available if a
  label is wanted later, but not in v1.
- Vanilla spectator mode / `interactionManager.gameMode` changes. This is a pure
  client-side camera illusion.

## Architecture

The mod already contains a **working orbit-camera rig**: the spawn cinematic
detaches the camera, orbits it in the sky looking back at the grounded body,
forces third-person, and locks input. OOC is the same rig with the scripted
timeline replaced by live mouse control. We mirror that architecture rather than
invent a new one.

### Components

| Component | Type | Responsibility |
|---|---|---|
| `OocCameraController` | new Kotlin `object` | Owns OOC state (`isActive`, orbit `azimuth`/`elevation`/`distance`). Registers the keybind, ticks input, and computes the smooth per-frame camera transform. |
| `OocCameraMixin` | new Java mixin (`@Mixin(Camera.class)`) | At `Camera#update` TAIL, when `OocCameraController.isActive`, overrides camera pos + rotation and sets `thirdPerson = true`. |
| `MouseMixin` | edit existing | When OOC active, route mouse deltas into `OocCameraController` orbit (instead of turning the body). |
| `PlayerFreezeMixin` | edit existing | Add `|| OocCameraController.isActive` to the movement-zeroing guard so the body doesn't walk while orbiting. |
| `NPCMessageParserClient` | edit existing (entrypoint) | Call `OocCameraController.register()` at init and `OocCameraController.tick()` inside `ClientTickEvents.END_CLIENT_TICK`. |

### Reference files (existing prior art to model on)

- `src/client/kotlin/com/canefe/storyclient/client/cinematic/SpawnCinematicController.kt`
  — orbit `cameraPos(tickDelta)` / `cameraYaw()` / `cameraPitch()` math to mirror.
- `src/client/java/com/canefe/storyclient/client/mixin/SpawnCinematicCameraMixin.java`
  — the `Camera#update` TAIL injection to clone (guard on `OocCameraController`
  instead of the cinematic controller).
- `src/client/java/com/canefe/storyclient/client/mixin/CameraAccessor.java`
  — provides `storyclient$setPos`, `storyclient$setRotation`,
  `storyclient$setThirdPerson`.
- `src/client/java/com/canefe/storyclient/client/mixin/MouseMixin.java`
  — existing mouse-delta capture point (currently zeroes deltas during the
  cinematic); add an OOC branch that feeds deltas to the controller.
- `src/client/java/com/canefe/storyclient/client/mixin/PlayerFreezeMixin.java`
  — existing movement-freeze guard to extend.
- `src/client/kotlin/com/canefe/storyclient/client/permission/PermissionKeybinds.kt`
  — the `register()` + `tick()` keybind-module pattern to follow.
- `src/client/kotlin/com/canefe/storyclient/client/NPCMessageParserClient.kt`
  — entrypoint; keybind registration ~line 408, tick polling in
  `END_CLIENT_TICK` ~line 434.
- `src/client/resources/storyclient.client.mixins.json`
  — register the new `OocCameraMixin` class name here.

## Data Flow

1. **Toggle on** — `O` pressed → `OocCameraController.toggle()` sets
   `isActive = true` and seeds `azimuth`/`elevation` from the current camera
   yaw/pitch so the transition doesn't jump.
2. **While active (per tick)** — `MouseMixin` routes deltas → controller updates
   `azimuth`/`elevation` (mouse) and `distance` (scroll). `PlayerFreezeMixin`
   zeroes movement input so the body stays put.
3. **While active (per render frame)** — `OocCameraMixin` at `Camera#update`
   TAIL reads `cameraPos/Yaw/Pitch(tickDelta)`, writes them onto the Camera, and
   sets `thirdPerson = true` so the body renders. Uses `tickDelta` for smooth
   interpolation.
4. **Toggle off** — `O` pressed again → `isActive = false`. All mixins fall
   through to vanilla; the camera reattaches to the body. No cleanup needed.

## Orbit Math (v1)

- **Camera position** = `playerEye + spherical(azimuth, elevation, distance)`,
  where `playerEye` is the local player's interpolated eye position at
  `tickDelta`.
- **Look-at** = player eye, so the body is always framed.
- **Clamps:**
  - `elevation ∈ [-80°, +80°]` (avoid gimbal flip at the poles).
  - `distance ∈ [2.0, 12.0]` blocks (scroll-clamped).
- **Collision:** none in v1. The camera may clip through terrain; acceptable for
  a spike.
- **Smoothing:** position/rotation are computed from `tickDelta` in the render
  frame (like `SpawnCinematicController`), not from the tick, so motion is smooth
  independent of tick rate. Orbit *targets* (azimuth/elevation/distance) are
  updated in tick/mouse handlers.

## Input

- **Keybind:** `O`, category `key.categories.storyclient`. Free — F/R/V/Y/J/H/B/
  G/K are already taken. Rebindable via Minecraft controls.
- **Mouse:** deltas drive azimuth (horizontal) and elevation (vertical) while OOC
  active. Sensitivity reuses the vanilla look sensitivity feel; a fixed
  multiplier is fine for v1.
- **Scroll:** changes `distance` within the clamp range.
- The body receives **no** movement while OOC active (frozen). Other keybinds
  (chat, etc.) are not specially suppressed in v1.

## Error Handling / Edge Cases

- **No local player** (e.g. on a loading screen): `tick()` and the mixin guard on
  `client.player != null`; if absent while `isActive`, treat as inactive for that
  frame (no crash, no transform).
- **Toggle while another camera rig is active** (spawn cinematic): the spawn
  cinematic owns the camera during its run. v1 rule: OOC toggle is ignored while
  the spawn cinematic `isActive` (the cinematic controller wins). Simple guard in
  `toggle()`.
- **Dimension change / death while OOC:** on the next frame with a valid player,
  the orbit re-anchors to the (possibly new) player position. If the player
  entity is gone, OOC falls through to inactive rendering until it returns.

## Testing

Fabric client camera code is not unit-testable in isolation; verification is
manual in a running client:

1. Toggle `O` in survival → camera detaches, body renders in third person, body
   stays put.
2. Mouse orbits around the body; elevation clamps at the poles; scroll zooms
   within `[2, 12]`.
3. Toggle `O` again → camera snaps back to the body, normal look/movement
   restored.
4. Toggle during the spawn cinematic → no effect (cinematic wins).
5. No crash when toggling on a loading/death screen.

Any pure helper extracted (e.g. a `spherical(azimuth, elevation, distance)`
function) can carry a small Kotlin unit test, but that's optional.

## Future Work (not v1)

- Server-push OOC trigger: an S2C message (StoryMC sender + a client receiver)
  that calls `OocCameraController.setActive(true/false)` — the toggle is written
  as a plain public function so this drops in without refactoring.
- Camera collision / auto-framing.
- OOC HUD label using `SelfCharacterState.name`.
- Sim/director puppeting of the idle body while the player is OOC.
