# Tips Center — v1 Design

**Date:** 2026-07-08
**Repo:** StoryClient (Fabric 1.21.1, Kotlin)
**Status:** Approved design, ready for implementation plan

## Summary

A client-side "tips center": a code-defined registry of one-time tips. Trigger
points in the client call `TipManager.show(id)` at teachable moments (e.g. the
first time the player enters OOC camera). Each tip fires **once ever** — the
manager shows it via Immersive Messages only if its id has not been seen, then
records the id in a persisted seen-set. Players can reset all progress from the
mod-config menu so every tip can fire again.

## Scope

**In scope (v1):**

- `TipManager` singleton: a registry of tip definitions + `show(id)` + seen-set.
- Each tip declares its own display style (`TOAST` or `POPUP` — per-tip).
- Rendering via the existing `UIMessages` facade over Immersive Messages, using
  the library's `toast(duration, title, subtitle)` and
  `popup(duration, title, subtitle)` presets.
- Seen-set persisted to its own file `config/storyclient-tips.json` (separate
  from `storyclient.json` settings).
- A clickable "Reset tip progress" text-description entry in the cloth-config
  screen that runs a client command to clear the seen-set.
- A `storyclient-tips-reset` client command (Fabric `ClientCommandManager`) that
  calls `TipManager.resetProgress()` — the link's target and also usable directly.
- At least one real trigger wired: first OOC-camera activation shows a tip.

**Explicitly out of scope (v1 — YAGNI):**

- Server-pushed tips (all tips are client-defined and client-triggered).
- A seen-count display / progress readout in the config menu.
- Tip categories, ordering, queuing, or rate-limiting beyond "once ever."
- A real cloth-config push-button (cloth has none; the momentary toggle is the
  idiom).
- Localization files — tip strings are inline for v1 (English), matching how the
  rest of the client's UI strings are authored today.

## Architecture

### Components

| Component | Type | Responsibility |
|---|---|---|
| `TipStyle` | new Kotlin enum | `TOAST` \| `POPUP` — which Immersive Messages preset a tip uses. |
| `Tip` | new Kotlin data class | One tip definition: `id`, `title`, `subtitle`, `style`, optional `durationSecs`. |
| `Tips` | new Kotlin registry (object) | The static list of all `Tip`s, keyed by id. Single place tips are authored. |
| `TipManager` | new Kotlin object | `show(id)` (fires once, guarded by seen-set), `hasSeen(id)`, `markSeen(id)`, `resetProgress()`, `load()`/`save()` for the seen-set file. |
| `UIMessages` | edit existing | Add `toast(...)` and `popup(...)` wrappers over the Immersive Messages presets (the facade already owns the IM boundary). |
| `StoryClientConfigScreen` | edit existing | Add a clickable "Reset tip progress" text-description entry whose click runs the reset command. |
| `NPCMessageParserClient` | edit existing (entrypoint) | Call `TipManager.load()` at init; register the `storyclient-tips-reset` client command alongside the existing ones. |
| `OocCameraController` | edit existing | Call `TipManager.show("ooc_camera")` on first activation (the first real trigger). |

### Persistence

- File: `config/storyclient-tips.json`, owned entirely by `TipManager`.
- Shape: `{ "seen": ["ooc_camera", "..."] }` — a Gson-serialized DTO holding the
  seen-id set (stored/serialized as a list, held in memory as a `MutableSet`).
- `load()` reads it if present (empty set otherwise); `save()` writes it.
- Written on every `markSeen` and on `resetProgress`. Small file, infrequent
  writes — no batching needed.
- Kept separate from `StoryConfigData` so the positional settings DTO doesn't
  churn and a dynamic set isn't mixed into the settings schema.

## Data Flow

1. **Init** — entrypoint calls `TipManager.load()` → seen-set populated from disk.
2. **Trigger** — some client code hits a teachable moment and calls
   `TipManager.show("ooc_camera")`.
3. **Guard** — `show(id)` looks up the `Tip` in `Tips`; if the id is unknown it
   no-ops (defensive). If `hasSeen(id)`, it no-ops.
4. **Display** — otherwise it routes to `UIMessages.toast(...)` or
   `UIMessages.popup(...)` by the tip's `style`, using its `title`/`subtitle`/
   `durationSecs`.
5. **Record** — `markSeen(id)` adds the id and calls `save()`. The tip never
   fires again unless reset.
6. **Reset** — player enables the "Reset tip progress" toggle and closes the
   screen; its save-consumer calls `TipManager.resetProgress()` (clears the set,
   saves) and leaves the stored toggle value false, so it is a one-shot.

## Display (Immersive Messages)

`UIMessages` gains two preset wrappers mirroring the library helpers shown in the
IM docs, so tip call sites stay one-liners and the IM dependency stays behind the
existing facade:

- `toast(title, subtitle, duration)` → `ImmersiveMessage.toast(duration, title,
  subtitle)` sent to the local player. Top-left, achievement-style, unobtrusive.
- `popup(title, subtitle, duration)` → `ImmersiveMessage.popup(duration, title,
  subtitle)` sent to the local player. Centered above the hotbar, more intrusive.

Both grab the local player internally and no-op if absent, consistent with the
existing `UIMessages` methods. House font (ROBOTO) applied where the preset
allows configuration; otherwise the preset defaults stand.

## Config Menu

A clickable text-description entry in the cloth-config screen. Cloth has no
push-button, but a `TextDescription` renders styled `Text`, and a `ClickEvent`
on that text runs a client command — the same technique ETF-style screens get
from vanilla `ButtonWidget`s, done in-framework.

- Entry: `entryBuilder.startTextDescription(Text)` where the Text carries a
  `Style` with `ClickEvent(RUN_COMMAND, "/storyclient-tips-reset")` and a
  `HoverEvent` tooltip "Re-show all tips."
- Rendered as an underlined/accent-colored "Reset tip progress" link.
- Clicking runs the client command → `TipManager.resetProgress()` (clears the
  set, saves).

Client command (Fabric `ClientCommandManager`, registered next to the existing
`helixdebug`/`dmrealnames`/etc. in the entrypoint):

- `storyclient-tips-reset` → `TipManager.resetProgress()` + a chat confirmation
  ("Tip progress reset."). Usable directly from chat as well as via the link.

Placed in the General category (one entry).

## Registry (initial content)

At least one tip authored so the feature is live end-to-end:

- `ooc_camera` — style `POPUP`, title "Out of Character", subtitle "You've
  stepped out of your body. Move the mouse to orbit, scroll to zoom, press O to
  return." (Exact copy is tunable; this documents intent, not final strings.)

Additional tips are added by appending to `Tips` and calling
`TipManager.show(id)` from the relevant trigger. No other wiring required.

## Error Handling / Edge Cases

- **Unknown id** passed to `show` → no-op (logged at debug, not an error).
- **No local player** at show time → `UIMessages` no-ops; the tip is NOT marked
  seen (so it can fire once a player exists). Rationale: marking-seen only on a
  successful display avoids "consuming" a tip the player never saw.
- **Corrupt/missing tips file** → treated as empty seen-set; a fresh file is
  written on the next `save()`.
- **Concurrent access** — seen-set is read on the client thread (show/config) and
  loaded once at init; no cross-thread writes expected. If a future trigger fires
  off-thread, wrap the set in a synchronized access (not needed for v1).

## Testing

- Pure-logic unit test (Kotlin, no MC runtime): `TipManager` with an injected
  temp file path — `show` marks seen and persists; a second `show` no-ops;
  `resetProgress` clears; `load` round-trips the file. This is the one genuinely
  testable unit and should have coverage.
- Manual in-client: enter OOC the first time → popup appears; toggle OOC again →
  no popup; reset progress in config → OOC popup appears again on next entry.

## Future Work (not v1)

- Server-pushed tips (a channel that calls `TipManager.show`).
- Seen-count / progress readout in config.
- Per-tip cooldowns or "show N times" instead of once-ever.
- Localization of tip strings.
