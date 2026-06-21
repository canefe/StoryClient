# Handoff: Player-Initiated Wound Tending (bandage → click "Tend" in Health tab)

## Goal

Let the **local player** tend their own wounds from the StoryClient Health tab:

1. **bandage usable across repos** — a `bandage` (and the other medicine tiers)
   exists as a sim item already; make it a real Minecraft item the player can
   hold, so "do I have medicine?" is answerable in-game.
2. **Hover + click a wound row** in the Health tab. Hovering a wound (e.g.
   `cut` on Left Arm) shows a tooltip; if the player **has a medicine item**, a
   **"Tend"** affordance appears.
3. **Click "Tend"** → consumes one medicine from the player's inventory and
   starts tending that specific wound. Surface **tend quality** and the wound's
   resulting faster healing (the "progress").

This rides on the blood/bleeding system already shipped in story-sim. **Read
that system's design+plan first** so you don't re-derive it:
- `story-sim/docs/superpowers/specs/2026-06-20-blood-system-design.md`
- `story-sim/docs/superpowers/plans/2026-06-20-blood-system.md`

## TL;DR — what EXISTS vs what's MISSING

The hard sim mechanics are **done**. The new feature is mostly **plumbing a
player→sim "tend this specific wound" intent** + **client UI** + **one wire
field**. Do NOT rebuild the tend math.

### EXISTS (reuse, do not reimplement)
- **story-sim**: medicine item defs (`bandage` potency 0.30, `herbal_medicine`
  0.60, `medicine` 1.00, `glitterworld_medicine` 1.60) at
  `packs/BaseGame/lua/defs/items/`; `ItemDef.potency` + loader; `ActiveHediff.tended_quality`
  (0..1); `tend_quality_for(item_id, potency)` (caps per tier) and the whole
  `blood_loss_system` in `src/systems/blood_system.rs`; the `TendWoundRequest`
  struct + `process_tend_wound_queue` drain in `src/plugins/lua_world_api.rs`
  (resolves entities, looks up potency, picks worst-bleeding improvable wound,
  consumes medicine from the doctor's `Inventory`, stamps `tended_quality`). A
  tended wound's bleed → ~0 and it heals at `(1+tended_quality)×` rate (see
  `hediff_system.rs`).
- **StoryMC**: player inventory sync (`PlayerInventoryTracker` → signed
  `ItemDeltaIntent`); sim-id ↔ MC item mapping (`ItemIdResolver`, `ItemProvider`,
  `items.yml`, PDC tag `story:item_id`); the hediff S2C relay (`HediffRelay` →
  `story:hediffs` / `story:npc_hediffs`); the C2S listener pattern
  (`HediffWatchListener` via PacketEvents).
- **StoryClient**: `HealthView`/`HealthPanel` render wound rows; ImGui
  `selectable`/`button`/`isItemHovered` used in DM panels; the C2S payload
  pattern (`HediffWatchPayload`, channel `story:hediff_watch`).
- **story-go**: the `ItemDeltaIntent` forward (`ForwardItemDelta` →
  `give_item` NATS message) — the exact pattern to mirror for a tend intent.

### MISSING (build this)
- **story-sim**: a player-initiated tend entry point (the only path today is the
  NPC `tend_wound` joint task, and `char:tend_wound` is gated to `IN_ON_RESOLVE`).
  And **specific-wound targeting** (`TendWoundRequest` has no target wound; the
  drain always picks worst-bleeding).
- **wire**: `tended_quality` is NOT on the proto `Hediff`, `HediffDto`, or
  `HediffEntry` — the client can't show tend state without it.
- **bandage as MC item**: confirm/add `bandage` (+ tiers) to StoryMC `items.yml`
  so it resolves to a real `ItemStack`.
- **StoryClient**: wound-row hover/click UI, a "have medicine?" check, and a new
  C2S `tend_wound` payload.
- **StoryMC**: a `TendWoundListener` (C2S) + a `PlayerTendWoundIntent` domain
  event.
- **story-go**: a `ForwardPlayerTendWound` (intent → NATS `player_tend_wound`).

## Decisions to make BEFORE coding (do not guess — confirm with the user)

1. **Tend = instant or channeled?** The sim drain stamps `tended_quality`
   instantly; the "progress" you see is then the wound healing faster over
   game-time (no separate timer). RimWorld-style tending is an instant treat +
   ongoing heal. **Default recommendation: instant stamp, show quality + the
   now-faster heal as "progress."** Only build a cast/channel timer if the user
   wants a visible "tending… X%" bar during a fixed duration (bigger: needs a
   per-wound in-progress state on the wire + a sim timer).
2. **Specific wound vs worst-wound?** The feature is "click THIS cut." So
   `TendWoundRequest` needs an **optional `target_wound_key`** (e.g.
   `"cut@left_arm"`); when present the drain tends that exact wound (still gated
   by "would improve it": `tended_quality < new quality`), else falls back to
   worst-bleeding. Confirm the wound key the UI sends matches the sim map key
   (it's `id@body_part`, e.g. `cut@left_arm` — verify against `Hediffs::key_for`).
3. **Who can the player tend — self only?** Default: **self only** (the player's
   own character). DM-tending NPCs is out of scope for v1 (the NPC joint-task
   path already covers NPC-doctor tending).
4. **"Have medicine?" check — client-side or server-validated?** The Fabric
   client can read the player's own MC inventory directly (it's their client),
   so the **button gate** can be client-side (cheap, responsive). The **server
   must still validate** on receipt (don't trust the client) — the sim drain
   already consumes from inventory and no-ops if absent, so this is mostly free;
   but StoryMC should confirm the player actually holds the item before
   forwarding, to avoid a wasted round-trip. Decide how the client enumerates
   "which medicine to use" — auto-pick best tier held, or let the player choose.
5. **`tended_quality` wire = add to the existing hediff broadcast?** Yes —
   extend the proto `Hediff` (+ `HediffDto` + `HediffEntry`) with
   `tended_quality`. Backward-compatible (defaults 0.0). This is the SAME
   multi-repo field-add pattern already done for `body_part` — copy that PR's
   shape exactly (proto field N → story-go regen → `HediffDto` + adapter →
   `HediffDTO`/`HediffEntry`). See the blood-system plan's Task on `body_part`.

## Suggested build order (each step compiles/builds on its own)

> Use the **brainstorming** skill ONLY to settle the 5 decisions above, then
> **writing-plans** → **subagent-driven-development** like the blood system.
> The repos use **jj** (commit = `jj describe` then `jj new`), NOT git branches.

### Phase 1 — bandage is a real, usable MC item
1. Confirm `bandage` (+ herbal_medicine/medicine/glitterworld_medicine) in
   StoryMC `run/plugins/Story/items.yml` (or wherever `items.yml` lives) map to
   a Material (or MythicMobs item). Verify `ItemIdResolver` round-trips:
   sim-id `"bandage"` → `ItemProvider.resolve()` → `ItemStack` with PDC
   `story:item_id="bandage"`, and the reverse (Material → sim-id) for the
   inventory snapshot. Test: give yourself a bandage in-game, confirm
   `PlayerInventoryTracker` emits `ItemDeltaIntent(item="bandage", qty=+1)` and
   the sim Inventory shows it (BRP-query the player entity's `Inventory`).

### Phase 2 — `tended_quality` on the hediff wire (mirror the body_part PR)
2. Add `tended_quality` (float, default 0) to: proto `Hediff` (next field
   number after `body_part = 6`), regenerate story-go proto, `HediffDto` +
   `SimEventAdapter` (StoryMC), `HediffDTO` + `HediffEntry` (StoryClient).
   Also confirm `entity_state_broadcast.rs` (sim) writes `ah.tended_quality`
   into the proto `Hediff` — that's the one sim-side emit edit.
3. StoryClient: show tend state on the wound row (e.g. a small "tended qX%"
   note, or tint the bar). No interactivity yet — just prove the value arrives.

### Phase 3 — player tend intent: client → MC → go → sim
4. **story-sim**: add `target_wound_key: Option<String>` to `TendWoundRequest`;
   in `process_tend_wound_queue`, when set, tend that exact wound (keep the
   "would improve" guard); add a stream handler `player_tend_wound.rs` that
   reads `{character_id, name, wound_key, medicine_id}` and enqueues a
   self-tend `TendWoundRequest` (doctor == patient == the player). Register the
   handler where the other stream handlers register (mirror `give_item.rs`).
5. **story-go**: `ForwardPlayerTendWound` — `player.tend_wound` BridgeMessage →
   publish NATS `player_tend_wound` (mirror `ForwardItemDelta` exactly).
6. **StoryMC**: `PlayerTendWoundIntent` domain event (`DomainEvents.kt`,
   `eventType="player.tend_wound"`, fields character_id/name/wound_key/medicine_id);
   a `TendWoundListener` (PacketEvents, channel `story:tend_wound`) that
   resolves the sender's character id, validates they hold the medicine, and
   emits the intent. Wire the intent → story-go forward (look at how
   `ItemDeltaIntent` reaches `ForwardItemDelta`).
7. **StoryClient**: `TendWoundPayload` (C2S, channel `story:tend_wound`,
   `tendWound(woundKey, medicineId)` — copy `HediffWatchPayload` shape) +
   register it in `NPCMessageParserClient`.

### Phase 4 — the Health tab UI (the visible feature)
8. In `HealthView`, make each injury row hoverable/clickable: wrap the
   severity bar in a `selectable`/invisible button; on `isItemHovered()` show a
   tooltip (wound name, severity, current tended quality). If the player holds a
   medicine (check the local MC inventory; auto-pick best tier or a small
   chooser per decision #4), show a **"Tend"** button. On click →
   `TendWoundPayload.tendWound("cut@left_arm", "bandage")`.
9. Feedback loop: after tending, the next `story:hediffs` broadcast (≤2s) shows
   `tended_quality > 0` on that wound and its severity dropping faster — the
   "progress." (If decision #1 chose a channeled cast, you'll instead need an
   in-progress wire field + sim timer — out of the default scope.)

## Gotchas / constraints (learned building the blood system)

- **Wound key format is `id@body_part`** (e.g. `cut@left_arm`); whole-body
  hediffs are the bare id. The UI must send the same key the sim's `Hediffs`
  map uses. Verify against `Hediffs::key_for` in `src/components/hediff.rs`.
- **The drain consumes medicine from the doctor's `Inventory`.** For self-tend,
  doctor==patient==player, so it consumes from the player's sim inventory —
  which is kept in sync by `PlayerInventoryTracker`/`ItemDeltaIntent`. The
  consume happens **sim-side**; the MC client's item count updates via the
  normal inventory sync, not by the client removing it locally. Don't double-
  consume (don't also remove the item client-side).
- **`day_scale` matters for visible "progress."** At `day_scale=1.0` healing
  crawls in real-time; verify tend effects via BRP severity queries, not just
  eyeballing. (BRP: `POST localhost:15702 world.query` on
  `story_sim::components::hediff::Hediffs`.) The blood test harness
  (`packs/BaseGame/lua/autorun/test_blood_system.lua`) spawns wounded NPCs and
  `cut.BleedPerSeverity` is currently cranked to 2.0 for visibility — dial back
  for realistic testing.
- **New outbound SerializableStoryEvent MUST be registered** in StoryMC's
  `WebSocketTransport.serializeEvent` or the payload silently drops to `{raw}`.
  (Applies if `PlayerTendWoundIntent` goes over the WS bridge.)
- **Don't trust the client** for "has medicine" — server validates on receipt;
  the sim drain is the final authority (no-ops if the item isn't there).
- **tended_quality wire add is the body_part PR again** — there's a worked
  example across all repos; copy its exact shape to avoid the proto/codegen
  gotchas (story-proto is a submodule shared by StoryMC + story-go; bump +
  `make proto` in story-go).

## Verify (per phase)
- Phase 1: BRP shows `bandage` in the player's sim `Inventory` after picking one
  up in MC.
- Phase 2: BRP `tended_quality` on a wound (tend an NPC via the existing joint
  task) shows up in the StoryClient Health tab.
- Phase 3: send a `story:tend_wound` from a throwaway client call (or a temp
  keybind) and watch `[Tend]` log + BRP `tended_quality` change on the player.
- Phase 4: in-game — hold a bandage, hover your cut, click Tend, watch the
  bandage count drop and the cut's bleed stop + heal faster.

## Out of scope (v1)
- Channeled/timed tend cast bar (default is instant-stamp + faster heal).
- DM tending NPCs from the client (NPC-doctor joint task already covers NPC
  tending).
- Surgery / operations, scarring, infection-from-bad-tend, tend-failure rolls.
- Medicine crafting; per-instance medicine quality (potency is per-ItemDef).
