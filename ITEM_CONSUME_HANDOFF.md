# Handoff: Item Consume in Minecraft → story-sim (eat bread → fill hunger)

## Goal

When a player **consumes an item in Minecraft** (eats bread, drinks a water
skin), apply that item's **consume effects** to their **player-character in the
sim** — e.g. eating bread raises the character's `hunger` need. The sim is the
authority for needs; MC is just the trigger.

Concretely for v1: **eat bread in MC → the sim character's `hunger` need goes
up by bread's defined amount.**

## TL;DR — what EXISTS vs what's MISSING

The MC→sim *plumbing already exists end-to-end* — eating bread **already sends a
signal to the sim today**. What's missing is the sim **doing something with it**:
reading the item's `ConsumeEffects` and applying them to needs. Most of this
feature is one new sim stream handler + deciding the `ConsumeEffects` data shape.

### Already works (do NOT rebuild)
- **MC catches the eat.** `PlayerInventoryListener.onConsume` (Bukkit
  `PlayerItemConsumeEvent`) → `playerInventoryTracker.scheduleDiff(player, "consume")`.
  File: `Story/src/main/kotlin/com/canefe/story/event/PlayerInventoryListener.kt:35`.
- **MC emits a signed delta to the sim.** `PlayerInventoryTracker.scheduleDiff`
  diffs the inventory next tick and emits `ItemDeltaIntent(characterId, name,
  item="bread", qty=-1, source="consume")`. File:
  `Story/src/main/kotlin/com/canefe/story/bridge/PlayerInventoryTracker.kt` (the
  `for ((simId, delta) in diff...)` loop).
- **The intent reaches story-go.** `ItemDeltaIntent` (eventType `"item.delta"`)
  is registered in `WebSocketTransport.serializeEvent` (`DomainEvents.kt` /
  `WebSocketTransport.kt:265`).
- **story-go forwards it to the sim.** `ForwardItemDelta`
  (`story-go/internal/sim/handler.go`) publishes NATS `give_item` with the signed
  qty; the sim's `give_item` handler routes a negative qty to
  `Inventory::remove_item`. So **today, eating bread already decrements the bread
  in the sim Inventory** — it just produces no need change.
- **Item defs already carry consume metadata.** `ItemDef.consume_effects:
  Vec<String>` is parsed from Lua `ITEM.ConsumeEffects`
  (`story-sim/src/resources/mod.rs` + `src/plugins/definition_loader/items.rs`).
  `bread.lua` has `ConsumeEffects = { "eat_food" }`; `apple`, `wild_berries`
  same; `water_skin` has `{ "drink_water" }`.
- **Needs are mutable.** `char:modify_need("hunger", 40.0)` is the canonical
  fill — see `packs/BaseGame/lua/defs/behaviors/eat_bread.lua:36` (the existing
  NPC eat path). Backed by `ModifyNeedRequest` + `apply_need_request`
  (`lua_world_api.rs`) and the `set_need` stream handler
  (`stream_handlers/set_need.rs`) — the latter is the **template** for the new
  handler.
- **character_id → entity resolution** uses `ExternalId` (`lua_world_api.rs`
  set_need/set_stat resolver: `eid.id == req.character_id`, else fall back to
  name). Player-character id flows via `Player.characterId`
  (`util/PlayerUtils.kt`).

### Missing (build this)
- **The sim ignores `ConsumeEffects` on consume.** Nothing reads an item's
  `consume_effects` when it's consumed. `ConsumeEffects = { "eat_food" }` is
  currently **decorative** — no code maps `"eat_food"` → a hunger change. (The
  NPC eat path fills hunger via a *behavior hook* `char:modify_need`, NOT via the
  item's ConsumeEffects.)
- **No consume signal distinct from a plain inventory removal.** Eating bread
  currently looks identical to dropping it: both arrive as
  `ItemDelta(qty=-1)`. The sim needs to know "this removal was a **consume**" so
  it applies effects. (`source="consume"` is already on the StoryMC side but is
  **dropped** — `ForwardItemDelta` doesn't forward `source`.)
- **No `consume_item` sim stream handler** to look up the item def, read its
  effects, and apply them to needs.

## THE design decision (settle before coding)

**`ConsumeEffects` is currently a `Vec<String>` of opaque names (`"eat_food"`)
with no definition.** Something must turn `"eat_food"` into "hunger += 40". Pick
one:

1. **(Recommended) Make ConsumeEffects data-driven.** Change the Lua shape to
   carry the need + amount directly, e.g.:
   ```lua
   ITEM.ConsumeEffects = {
       { Need = "hunger", Delta = 40.0 },
   }
   ```
   The new sim handler reads `item.consume_effects` and enqueues one
   `ModifyNeedRequest` per entry. **Pros:** zero indirection, designers edit the
   item file, no effect registry. **Cons:** changes the `consume_effects` parse
   shape (currently `Vec<String>`) → becomes `Vec<ConsumeEffect{ need, delta }>`;
   touch `ItemDef` + the items.rs loader. Empty `ConsumeEffects = {}` items
   (medicine, coins) stay no-op.
2. **Keep string ids, add an effect→need lookup table.** Keep
   `ConsumeEffects = { "eat_food" }`; define a sim-side map `"eat_food" →
   (hunger, +40)`, `"drink_water" → (thirst, +X)`. **Pros:** no item-file
   change. **Cons:** the mapping lives in code/another file, drifts from the
   item; you still have to define every effect.
3. **Reuse the action/effect system.** `actions/eat_food.lua` exists with
   `ACTION.Effects = { "eat_food" }`, applied via the Lua `Effects` global +
   `apply_effects` (`execution.rs:2716`). BUT there is **no `effects/eat_food.lua`
   file** — the eat effect isn't actually defined there (NPC eat fills hunger via
   the `eat_bread` behavior hook, not an Effect). Wiring consume through
   `apply_effects` means first authoring the missing `eat_food`/`drink_water`
   Effects. **Heaviest; only worth it if you want MC-consume and NPC-eat to share
   one declarative effect system.**

**Recommendation:** Option 1 for v1 (data-driven, simplest, self-contained).
Revisit Option 3 later if NPC-eat is refactored onto the same effects.

Also decide:
- **Need clamping** — `modify_need` already clamps to the need's range (confirm
  in `Needs::modify`); over-eating just caps at full. Confirm, don't re-clamp.
- **Cancelled/failed eats** — `onConsume` is `ignoreCancelled = true`, fires at
  `MONITOR`, so only successful consumes reach it. Good.
- **Stacked/partial** — `PlayerItemConsumeEvent` is one item per event; the diff
  yields qty −1. Multi-item consume isn't a concern.

## Build order (each step builds on its own)

> story-sim uses **jj** (`jj describe` then `jj new`); story-go + StoryMC use
> **git**. Conventional commits, no Co-Authored-By. The proto is ONE shared
> submodule (`story-proto`) used by all three repos — if you add a proto field,
> commit+push it once to story-proto main and bump each superproject's gitlink
> (see the tend-wound feature for the worked pattern). **This feature needs NO
> proto change** if you ride the existing `item.delta`/`give_item` JSON wire
> (recommended) — see Phase 2.

### Phase 1 — sim: ConsumeEffects shape + a consume applier (the real work)
1. **Settle the data shape (decision above).** If Option 1: add
   `ConsumeEffect { need: String, delta: f32 }`, change `ItemDef.consume_effects`
   to `Vec<ConsumeEffect>`, update the loader in
   `src/plugins/definition_loader/items.rs` to parse the table-of-tables, and
   update `bread.lua`/`apple.lua`/`wild_berries.lua` to
   `{ { Need = "hunger", Delta = 40 } }` and `water_skin.lua` to
   `{ { Need = "thirst", Delta = ... } }`. Keep empty `{}` for non-consumables.
2. **Create `src/plugins/stream_handlers/consume_item.rs`** (mirror
   `set_need.rs` + `give_item.rs`):
   - `register`: `msg_types: &["consume_item"]`.
   - `handle`: read `character_id`, `name`, `item`, `qty` (default 1). Look up
     the item in `ItemRegistry` (the handler ctx already exposes registries; see
     how `process_tend_wound_queue`/give_item reach `ItemRegistry`). For each
     `ConsumeEffect` in `item.consume_effects`, enqueue a `ModifyNeedRequest {
     character_id: Some(cid), entity_name: name, need_id: effect.need, delta:
     effect.delta * qty, op: NeedStatOp::Modify }` onto `ctx.lua_queues.modify_needs`.
   - Resolve by `character_id` (ExternalId) first, name fallback — the
     `modify_needs` drain already does this; you just pass both.
3. **Register** the handler in `stream_handlers/mod.rs` `handlers()` (add
   `mod consume_item;` + `consume_item::register(&mut entries);`).
4. **TDD:** a sim unit test — build a character with `hunger` at 30, enqueue a
   `consume_item` for `bread`, run the modify-needs drain, assert `hunger`
   increased by bread's delta. Model setup on the existing need/blood tests.

### Phase 2 — wire the consume signal (MC → go → sim)
The existing `item.delta` path already removes the item from the sim inventory.
You need a SECOND signal that says "apply consume effects." Two options:

**(Recommended) New `item.consume` intent alongside the existing delta.**
- **StoryMC**: in `PlayerInventoryTracker`, when `source == "consume"`, in
  addition to (or instead of — see note) the `ItemDeltaIntent`, emit a new
  `ItemConsumeIntent(characterId, name, item, qty)` (eventType
  `"item.consume"`). Register it in `WebSocketTransport.serializeEvent`. NOTE:
  the bread is consumed in MC, so the `-1 ItemDelta` should STILL fire to keep
  sim inventory in sync; the consume intent is purely additive (effects only).
  Keep them separate so inventory sync and effects don't entangle.
- **story-go**: add `ItemConsume = "item.consume"` to `pkg/events/events.go`; add
  `ForwardItemConsume` (mirror `ForwardItemDelta`) publishing NATS `consume_item`
  with `{character_id, name, item, qty}`; add the dispatch case in
  `internal/server/server.go`.
- **sim**: the `consume_item` handler from Phase 1 receives it. Done.

**(Alternative) Carry `source` on the existing item.delta.** `ForwardItemDelta`
already has the data but drops `source`; forward it, and have the sim's
`give_item`/delta path trigger consume effects when `source=="consume"` and
qty<0. **Rejected for v1** — overloads inventory-sync with effect logic and
coupling; the separate intent is cleaner.

### Phase 3 — verify
- **Phase 1:** `cargo test` the consume unit test green.
- **Phase 2/3 in-game:** give yourself bread (`/story item give bread`), let the
  sim character's hunger decay (or `maja:modify_need("hunger", -70)` style), eat
  the bread, and BRP-query the character's `Needs` — `hunger` should jump by
  bread's delta. (BRP: `POST localhost:15702 world.query` on
  `story_sim::components::needs::Needs`.)
- Confirm the bread is also gone from both MC inventory and the sim Inventory
  (existing item.delta path) — and that eating it didn't double-fire.

## Gotchas / constraints (from adjacent features)

- **The consume already partially flows.** Don't be surprised that eating bread
  already changes the sim — that's the `item.delta` inventory removal. Your job
  is the *effect*, not the removal.
- **`ConsumeEffects` is currently dead data.** Nothing reads it. Confirm your
  new handler is the first reader.
- **character_id is the player's identity, NOT entity name.** The sim resolves a
  player by `ExternalId == character_id` (the player's sim-entity Name is not the
  MC username). Always pass `character_id`; name is only a fallback. (This bit
  the tend-wound feature — see its handoff.)
- **`source` is dropped by story-go today.** `ForwardItemDelta` does not forward
  `msg.Data["source"]`. If you choose the Alternative wire, you must add it.
- **New outbound `SerializableStoryEvent` MUST be registered** in
  `WebSocketTransport.serializeEvent` or it silently serializes to `{raw}`.
- **modify_need clamps** to the need's defined range — over-eating caps at full;
  don't add your own clamp.
- **NPC eat is a different path** (`behaviors/eat_bread.lua` hook
  `char:modify_need("hunger", 40)`). v1 does NOT unify them; if you later want
  one path, that's Option 3 (author real `Effects` and route both through
  `apply_effects`).

## Out of scope (v1)
- Drink/thirst, sleep/rest, or any consume effect beyond the need-fill data the
  item declares (the handler is generic — adding `water_skin → thirst` is just
  item-file data once the shape exists).
- Hediff-from-consume (e.g. a buff/poison food) — `ConsumeEffects` could grow a
  `Hediff` variant later; v1 is needs only.
- Unifying NPC-eat and player-consume onto one effect system (Option 3).
- Consume animations / sounds / client feedback (MC already plays the vanilla
  eat animation).
