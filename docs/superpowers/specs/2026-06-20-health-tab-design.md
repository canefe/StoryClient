# Health Tab (RimWorld-style) — Design

Date: 2026-06-20
Repos touched: story-sim, story-proto, story-go, Story (StoryMC), StoryClient

## Goal

A standalone **Health window** in StoryClient (Fabric 1.21.1) showing the **local
player's** health, modeled on the RimWorld health screen ("Nice Health Tab"):

- Paper-doll figure on the left.
- Whole-body conditions with severity bars/% in the middle-top.
- Per-body-part injury lists, with injured parts highlighted on the figure.

To support per-body-part grouping, a `body_part` field is added to the hediff
data model end-to-end (5 repos). It does not exist upstream today.

## Decisions (settled)

- **Subject:** local player only.
- **Placement:** standalone ImGui window, own keybind (**H**), NOT a DM-panel tab.
- **Fidelity:** full RimWorld layout (paper-doll + conditions + per-part lists).
- **Window frame:** reuse the single existing ImGui frame — NOT a second
  `newFrame()`. The only thing gating panels off is `if (!active) return` in
  `DMPanelManager.render()`; relax that gate so the Health window renders when DM
  mode is off, while DM panels still require `active`.
- **body_part source:** combat `DamageOutcome.part_hit`. Whole-body conditions
  (malnutrition, fainted) carry no part. Empty string `""` is the whole-body
  sentinel on the wire (not null) to keep JSON/proto defaults clean.
- **Body type for v1:** `baseliner/male/`. Other xeno-types are a follow-up.

## Part 1 — `body_part` wire (5 repos)

End-to-end flow today:

```
story-sim ActiveHediff → entity_state_broadcast (proto Hediff)
  → NATS → story-go (forwards SimEvent)
  → WS → StoryMC SimEventAdapter (HediffDto) → HediffRelay (JSON "story:hediffs")
  → StoryClient HediffPacketReceiver (HediffEntry)
```

### story-sim (Rust)

- `components/hediff.rs`
  - `ActiveHediff` gains `pub body_part: Option<String>`.
  - `Hediffs::apply()` gains a `target_part: Option<String>` parameter; stored on
    the constructed `ActiveHediff`.
- `systems/reaction_system.rs`
  - `ApplyHediffRequest` gains `pub body_part: Option<String>`.
  - `apply_queued_reactions_system` threads `req.body_part` into `apply()`.
  - The Lua-facing `apply_hediff` closure pushes `body_part: None` (no API change
    for v1).
- `systems/health_system.rs`
  - The combat-damage hediff queue push passes `body_part: outcome.part_hit.clone()`.
- `systems/stat_threshold_system.rs`, `systems/need_threshold_system.rs`
  - Pass `body_part: None` (whole-body) — these produce malnutrition/fainted etc.
- `plugins/entity_state_broadcast.rs`
  - Write `body_part: ah.body_part.clone().unwrap_or_default()` into proto Hediff.

`HediffDef` already has `targets_body_part` / `whole_body` flags; no schema change.

### story-proto

- `Hediff` message gains `string body_part = 6;` (empty = whole-body).
- Pushed to main (approved workflow).

### story-go

- Regenerate proto. No logic change (it forwards the SimEvent unmodified).

### StoryMC (Story plugin)

- `bridge/DomainEvents.kt`: `HediffDto` gains `val bodyPart: String = ""`.
- `bridge/SimEventAdapter.kt`: map `bodyPart = h.bodyPart`.
- `bridge/HediffRelay.kt`: add `bodyPart` to `signature()` so part changes
  trigger a re-send (currently only id/severity/stage are diffed).

### StoryClient

- `hediff/HediffPacketReceiver.kt`: `HediffDTO` gains `val bodyPart: String = ""`;
  receiver passes it into `HediffEntry`.
- `hediff/HediffHudState.kt`: `HediffEntry` gains `val bodyPart: String`.

Backward compatible: proto and JSON both tolerate the new field; old senders
produce `""` (whole-body), which is the correct default.

## Part 2 — Health window (StoryClient)

### Plumbing

- New `HealthState` (object): reads `HediffHudState.active` each frame, groups
  entries by `bodyPart` (`""` → whole-body bucket). Pure derived state; no new
  packets.
- New `HealthPanel` (object): own `open: ImBoolean`, `toggle()`, floating
  `ImGui.begin` — **not** docked into the DM dockspace, **not** in the DM
  `panels` list.
- `DMPanelManager.render()`: change gate to
  `if (!active && !HealthPanel.isOpen()) return`; after the DM `panels` loop,
  call `HealthPanel.render()` (which self-gates on `open`). DM panels remain
  gated on `active`.
- `DMPanelManager.wantsMouse()/wantsKeyboard()`: also return true when
  `HealthPanel.isOpen()` and ImGui wants capture, so input gating works outside
  DM mode. (These are currently defined but unconsulted; wiring callers is out of
  scope unless already needed — verify during build.)
- Keybind: register Health on **H** via the `NPCMessageParserClient`
  `END_CLIENT_TICK` + `KeyBindingHelper` pattern; `while (key.wasPressed())
  HealthPanel.toggle()`. Verify no runtime clash with the existing
  PermissionKeybinds "deny" H; if it clashes, gate by panel state or move one.

### Layout (reads `HealthState`)

- **Left — paper-doll:** layer `baseliner/male/{outline,torso,head,neck,
  upperarm,lowerarm,hand,leg,feet}.png` via `ImGui.image`. Parts with any hediff
  whose `bodyPart` matches get a `common/bloodwound{1..3}.png` overlay / tint
  (intensity by max severity on that part).
- **Middle-top — conditions:** whole-body bucket as rows: `common/<icon>.png` +
  label + `progressBar(severityFraction(entry), ...)` colored green→yellow→red.
  Reuse existing `HediffHudState.severityFraction`.
- **Below/right — per-part injuries:** one header per non-empty `bodyPart`, its
  hediffs listed with severity bars.

### Build order

1. `body_part` wire (Part 1, all 5 repos) + compile each.
2. `HealthState` (group by part).
3. `HealthPanel` + keybind + `DMPanelManager` gate change.
4. Conditions section (bars + `common/` icons).
5. Paper-doll (`baseliner/male/`).
6. Per-part grouping + injury highlight on the figure.

## Don't break

The shipped pause/freeze pipeline (`PauseStatePacketBridge`, `PlayerFreezeMixin`
on `KeyboardInput`, `PauseOverlayHud`, the `pause/` package, the `SimPaused`
proto event). Unrelated — leave alone.

## Verify

- StoryClient compile:
  `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && /Users/canefe/Projects/personal/StoryClient/gradlew -p /Users/canefe/Projects/personal/StoryClient compileClientJava compileClientKotlin -q`
- StoryMC compile:
  `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin` (in Story).
- story-sim: `cargo build` (in story-sim).
- Verify MC field/method signatures via `javap -p` before any mixin change.

## License note

The `textures/health/` art is Andromeda's Workshop assets — fine for
private/local use; revisit before any public StoryClient release.
