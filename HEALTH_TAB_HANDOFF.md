# Handoff: StoryClient Health Tab (RimWorld-style)

## Goal
Build a **Health tab** in StoryClient (Fabric 1.21.1, Kotlin/Java mod at
`/Users/canefe/Projects/personal/StoryClient`) showing the **local player's**
health, modeled on the RimWorld health screen.

### Decisions already made (do NOT re-ask)
- **Subject:** Local player only (the player's own hediffs).
- **Placement:** A **standalone ImGui window** with its own keybind — NOT a tab
  inside the full DM panel. Reuse the same ImGui GUI system DM mode uses.
- **Fidelity:** **Full RimWorld-style layout** — character paper-doll figure on
  the left; top-level conditions with severity bars/% in the middle-top;
  per-body-part injury lists below/right.

## Reference
RimWorld mod **"Nice Health Tab"** by Andromeda (the exact UI in the user's
screenshot). Its art has been copied into this repo — see Asset Map below.

## Existing systems to build on (verified paths + symbols)

### Hediff data (local-player-scoped, already wired)
- `src/client/kotlin/com/canefe/storyclient/client/hediff/HediffPacketReceiver.kt`
  — decodes `story:hediffs` S2C custom payload; full-state replacement per send.
- `src/client/kotlin/com/canefe/storyclient/client/hediff/HediffHudState.kt`
  — `active: List<HediffEntry>`; helpers `severityFraction(entry)` (0..1; stage
  fallback Extreme→1f, Serious→0.6f, else 0.25f) and `shakePeriodMs(entry)`.
- `src/client/kotlin/com/canefe/storyclient/client/hediff/HediffHud.kt`
  — existing right-edge HUD column; per-hediff PNG at
  `assets/storyclient/textures/hediff/<id>.png` (fallback `unknown.png`).
- Data class:
  ```kotlin
  data class HediffEntry(val id:String, val severity:Float, val label:String, val stage:String, val description:String)
  ```
- **Gap:** `HediffEntry` has **no `bodyPart` field**, but the RimWorld layout
  groups by body part. Decide with user:
  - (a) ship without per-part grouping first (all under "Conditions"); **or**
  - (b) extend the wire (`HediffDTO` + `story:hediffs` payload + server emitter)
    to carry `bodyPart`.
  **Recommend (a) first**, (b) as follow-up. Confirm before touching server wire.

### ImGui panel system (DM mode)
- Render hook: `src/client/java/com/canefe/storyclient/client/mixin/MinecraftClientImGuiMixin.java`
  (injects after framebuffer blit; calls `DMPanelManager.INSTANCE.render()`).
- `src/client/kotlin/com/canefe/storyclient/client/dm/DMPanelManager.kt`
  — owns ImGui context/backends/dockspace; `active`, `wantsMouse()`,
  `wantsKeyboard()`; Inter-Medium font; constructs the panel list.
- `src/client/kotlin/com/canefe/storyclient/client/dm/DMPanel.kt`
  — `interface DMPanel { val type; fun render() }`.
- `src/client/kotlin/com/canefe/storyclient/client/dm/DMPanelType.kt`
  — enum + `isOpen()/setOpen()/begin()/end()`.
- Mirror: `src/client/kotlin/com/canefe/storyclient/client/dm/panels/InspectorPanel.kt`.
- ImGui widgets available: `text/textDisabled/textWrapped/separator/selectable`,
  `progressBar(fraction,w,h,label)`, `beginTable/tableSetupColumn/tableNextRow/
  tableSetColumnIndex`, `image(texId,w,h)`, `colorButton`.

### Standalone-window nuance (resolve early)
The DM ImGui overlay only renders when `DMPanelManager.active == true`. For a
**standalone** Health window with its own keybind that works OUTSIDE DM mode,
the ImGui loop must run for this window independently. Investigate
`DMPanelManager.render()` + `MinecraftClientImGuiMixin` and choose:
- Option A: an "always-eligible" panel list rendered even when `active==false`,
  gated per-panel by an open flag.
- Option B: a lightweight independent ImGui frame for standalone windows.
Pick the least disruptive to the DM flow; explain the choice to the user.
Add the keybind via the project pattern (see `PermissionKeybinds.kt` /
`ClientTickEvents.END_CLIENT_TICK` in `NPCMessageParserClient.kt`).

## Asset Map (already copied + lowercased into this repo)

Source workshop mod (reference only, do NOT ship from here):
`/Users/canefe/Library/Application Support/Steam/steamapps/workshop/content/294100/3328729902/`

Copied into: `src/client/resources/assets/storyclient/textures/health/`
(118 PNGs; all dirs/filenames lowercased — Fabric `Identifier` rejects
uppercase/spaces). Load via:
`Identifier.of("storyclient", "textures/health/<subdir>/<name>.png")`.

| Folder (`textures/health/…`) | Contents | UI region |
|---|---|---|
| `baseliner/{male,female,fat,hulk,kid}/` | Body-part figure pieces: `torso, head, arm, leg, hand, neck, shoulder, outline`, (male: `upperarm/lowerarm`) | **Paper-doll** — layer per body part to assemble figure |
| `baseliner/hand/`, `baseliner/feet/` | Per-finger/toe pieces: `index, middle, ring, little, thumb`, `handbase/feet`, `outline` | Detailed hand/foot views |
| `baseliner/head/` | `male eye/female eye` (+ `nice*`), `nose` | Head detail |
| `bones/` | `skull, spine, ribcage, pelvis` | Skeletal/injury overlays |
| `organs/` | `brain, heart, lung, liver, kidney, stomach` | Internal organ condition icons |
| `common/` | Condition icons: `blooddropextreme, disease, toxin, bandages(+half/bad), scars, pills, food, eyeicon, boneicon, organicon, dnaicon, hairicon, missingcross, bloodwound1-3, checkbox_checked/empty, gradient, whiteromb, armoricon` | **Top-condition rows** (Blood loss / Plague / Infection icons) + checkboxes + bars |
| `armor/` | `full, half, zero` | Armor coverage (optional later) |
| `prosthesis/` | `hook, woodenleg(+variants), tentacle(+long), fleshwhiplong, woodenpart, placeholder` | Prosthetic art (optional later) |
| `pigskin/`, `yttakin/` | xeno-type extras (`pignose`, `furrytail`) | Optional |

> `Materials/Bundles/` in the source are Unity AssetBundles (an `outline`
> shader) — **NOT usable** in Fabric. Only the `Textures/` PNGs were copied.

> **License caveat:** these are Andromeda's Workshop assets. Fine for
> private/local use; redistributing publicly without permission is not. The user
> accepted this for now — revisit before any public StoryClient release.

## Build / verify
- Compile:
  `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && /Users/canefe/Projects/personal/StoryClient/gradlew -p /Users/canefe/Projects/personal/StoryClient compileClientJava compileClientKotlin -q`
- **Shell gotcha:** cwd resets between Bash calls — always `gradlew -p <abs path>`
  or absolute paths; never bare `cd`.
- Verify MC field/method signatures by decompiling before mixing in:
  `javap -p` against
  `~/.gradle/caches/fabric-loom/minecraftMaven/.../minecraft-clientonly/1.21.1-...jar`
  (needs `JAVA_HOME` on PATH). Recent bug precedent: a mixin targeted a base
  class whose method was overridden by a subclass (`Input` vs `KeyboardInput`) —
  verify the actual runtime type.

## Process
- Feature build → use the **brainstorming skill** ONLY to confirm the two open
  decisions (per-bodypart data a/b; standalone-window approach A/B). User wants
  **no spec/plan docs** — keep it light, implement directly after confirmation.
- Suggested order: (1) `HealthState` holder reading `HediffHudState.active`;
  (2) standalone ImGui window + keybind; (3) conditions section via `progressBar`
  + severity colors (reuse `severityFraction`); (4) condition icons via
  `ImGui.image` from `textures/health/common/`; (5) paper-doll from
  `textures/health/baseliner/<type>/`; (6) per-body-part grouping (pending data
  decision a/b).

## Don't break
- The shipped **pause/freeze pipeline** is unrelated: `PauseStatePacketBridge`
  (StoryMC), `PlayerFreezeMixin` (on `KeyboardInput`), `PauseOverlayHud`, the
  `pause/` package, and the `SimPaused` proto event across story-sim/go/proto.
  Leave it alone.
