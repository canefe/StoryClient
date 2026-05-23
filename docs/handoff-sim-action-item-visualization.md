# Handoff: Visualizing Sim Actions & Item Transfers (story-sim → StoryMC → StoryClient)

## Goal

Make the simulation legible in-world. Right now NPCs driven by story-sim walk
around and trade silently — a player can't see *what* an NPC is doing or *that*
goods changed hands. Two visualizations:

1. **Active action above the head** — a fading text label over each sim-driven
   NPC showing its current action (e.g. "Heading to market", "Buying bread",
   "Begging", "Eating"). **Rendered client-side** via a dedicated packet (NOT
   server-side DecentHolograms) so the fade/animation is smooth and costs the
   server nothing. Reuse the *visual* fade behavior of the existing perception
   head-texts as a style reference, but the lifecycle is owned by the client.
2. **Item transfer holograms** — when items change hands (trade settle, gift,
   give), spawn a short-lived **client-side** floating item model that arcs from
   giver to receiver, so the exchange reads visually. Requires a
   **sim-item-id → Minecraft item** mapping (new YAML config in StoryMC).

This is a 3-repo feature: `story-sim` (Rust, emit events), `Story`/StoryMC
(Kotlin Paper plugin, relay + server-side holos + new item map), `StoryClient`
(Fabric client mod, Kotlin, client-side item holograms & HUD).

---

## Why this is "now" possible / what's missing

- The sim already broadcasts `npc.state` every tick
  (`story-sim/src/plugins/entity_state_broadcast.rs`, subject
  `story-sim.events`) with position + stats — but **no action/behavior field**.
  We know the active behavior exists on the entity: `Blackboard.active_behavior`
  and `ActiveActions.actions[slot].action_id` (verified live via BRP). It's just
  not on the wire yet.
- Item transfers actually happen in
  `story-sim/src/plugins/behavior/trade/lifecycle.rs::trade_settle_system`
  (~line 176–237). On settle it moves currency buyer→vendor (per `offer.wants`)
  and goods vendor→buyer (per `offer.gives`), then only `info!("trade {id}:
  settled — {buyer} <-> {vendor}")`. **No wire event is emitted.** That function
  is the canonical emit point for an item-transfer event. Gift/give paths
  (`request_gift` / `give_item` handle-methods) are the other emit points — find
  every place inventory moves between two entities.
- **No sim-item → MC-material mapping exists anywhere** (greped: StoryMC only
  hardcodes `Material.*` for quests). This must be authored.

---

## Existing surfaces to reuse (do NOT reinvent)

### StoryMC (Story repo, Kotlin)

- **Action label: REUSE the perception popup sender.** The fading head-texts are
  sent by `perception/PerceptionBroadcaster.kt` on channel `story:npc_perception`
  (see line ~238: `WrapperPlayServerPluginMessage("story:npc_perception",
  baos.toByteArray())`, encoding npcUuid + `PopupType` byte + label). Route the
  sim action label through this **same** broadcaster/channel with a new
  `PopupType.ACTION` — do NOT add a `story:npc_action` channel. Add a method like
  `PerceptionBroadcaster.sendActionPopup(npc, label)` that encodes with the
  ACTION type byte, audience = nearby players (mirror the existing popup
  audience). The PopupType byte ordinal must match the client enum.
- **Item transfer: NEW packet.** `combat/packet/CombatPacketBridge.kt` is the
  reference pattern — `story:*` plugin-message channel, `ByteArrayOutputStream`/
  `DataOutputStream` binary encode, `sendToAll(audience, channel, bytes)`. Add a
  `story:item_transfer` channel for it. (Item transfer doesn't fit the perception
  popup shape, so it gets its own channel — unlike the action label.)
- **Sim → MC state intake:** `bridge/IntentExecutor.kt::executeNpcStateIntent`
  consumes `npc.state` (`NpcStateIntent` in `bridge/DomainEvents.kt`). Extend
  `NpcStateIntent` with optional `actionId` + `actionLabel` fields (wire decode in
  `bridge/WebSocketTransport.kt` `deserializeEvent` when-branch; dispatched in
  `Story.kt` `eventBus.on<...>`). In `executeNpcStateIntent`, diff `actionLabel`
  vs the last value sent for that NPC and, on change, call
  `PerceptionBroadcaster.sendActionPopup(npc, label)`.

### StoryClient (Fabric, Kotlin, `com.canefe.storyclient.client`)

- Packet receiver + payload pattern: `client/combat/*` (e.g.
  `HitOutcomePayload.kt` + `CombatStatePushPayload.kt` decode the binary
  channels; `OutcomeBannerHud.kt` renders). Also `client/decision/
  DecisionPacketReceiver.kt`, `client/permission/PermissionPacketReceiver.kt`.
- **Action labels: REUSE THE PERCEPTION POPUP PATH EXACTLY.** The fading
  above-head texts the user means are
  `client/perception/PerceptionPopupRenderer.kt` (`onPerception(npcUuid, label,
  type)` queues a `Popup`; `render()` animates `yDelta`+`alpha` fade above the
  entity head), fed by the `story:npc_perception` packet (`NpcPerceptionPayload`,
  `PopupType` enum), registered/dispatched in
  `client/NPCMessageParserClient.kt` (~line 169–175). **Do NOT add a new
  `story:npc_action` channel or a new renderer.** Instead:
  - Add an `ACTION` variant to `PopupType` (client
    `perception/NpcPerceptionPayload.kt` enum: currently `PERCEPTION(0)`,
    `COMBAT_ATTACK(1)`, `COMBAT_ATTACKED(2)`, `MOOD(3)`, `AGGRESSION(4)` — add
    `ACTION(5)`) and give it a `PopupStyle` (icon/color) in
    `PerceptionPopupRenderer`.
  - Sim action labels ride the **existing** `story:npc_perception` packet with
    `type=ACTION`. No new payload class, no new receiver — `onPerception` already
    handles it.
- **Item transfers** still need a new path — add `client/sim/ItemTransferPayload`
  + receiver (mirror the `client/perception` / `client/combat` payload pattern).
- Item holograms are **client-side**: render a floating `ItemStack` model
  (vanilla item entity render / `ItemDisplay`-style billboard) that interpolates
  along an arc between two entity positions over ~0.6–1.0s, then fades. No server
  entity is spawned — purely visual, so it costs the server nothing and can be
  juicy (spin, bob, scale-in/out).

---

## Data contracts to add

### 1. Action label — authored on the behavior def (DECIDED)

**Which behaviors show a label, and what the label says, is authored on the
behavior def itself — in the behavior's Lua, alongside `BEHAVIOR.Name` etc.**
This is opt-in per behavior: a behavior with no `Label` shows nothing (so
`idle_stand`, `scan_environment`, etc. are silent for free, no denylist needed).

**Authoring (pack Lua), e.g. `packs/BaseGame/lua/defs/behaviors/buy_item.lua`:**
```lua
BEHAVIOR.Label = "Buying"          -- shown above the head while this behavior runs
```
Other examples: `head_to_known_location` → `"Heading somewhere"` (or make it
binding-aware later), `request_gift`/`request_alms` → `"Begging"`, `eat_bread`
→ `"Eating"`. Behaviors that should stay silent simply omit `BEHAVIOR.Label`.

**Sim plumbing:**
- `BehaviorDef` (`story-sim/src/resources/mod.rs`): add `pub label:
  Option<String>,`.
- Loader `story-sim/src/plugins/definition_loader/behaviors.rs::parse_one`
  (~line 195, the `BehaviorDef { ... }` literal): parse it with the existing
  optional-string helper — `label: extract_optional_string(&table, "Label"),`
  (mirrors how `description` is read at line 68).
- `entity_state_broadcast.rs`: resolve the current behavior id
  (`Blackboard.active_behavior`, or the highest-priority occupied
  `ActiveActions` slot's `action_id`→behavior), look up its `BehaviorDef.label`
  in `BehaviorRegistry`, and put it on the `npc.state` payload as `actionLabel`
  (omit/empty when the behavior has no label). Also ship the raw `actionId` so
  non-MC frontends can relabel/style as they like. The broadcaster needs read
  access to `BehaviorRegistry` (add it to the system's params).

**Throttle:** the existing `npc.state` cadence is fine on the wire, but StoryMC
must **diff `actionLabel` vs the last value it sent per NPC** and only emit the
`story:npc_action` packet when it changes (and an explicit clear when it goes
empty). Don't re-trigger the client every tick.

### 2. Item transfer event (sim → MC → client)

New sim event emitted from `trade_settle_system` (and every other inventory-move
point). Shape:

```
type: "npc.item_transfer"   (subject story-sim.events)
data: {
  fromCharacterId: string,
  toCharacterId:   string,
  item:            string,   // sim item id, e.g. "bread", "coin"
  qty:             int,
  reason:          string,   // "trade" | "gift" | "give"  (for styling)
}
```

MC decode → resolve both NPCs' entities → look up `item` in the new item map →
encode `story:item_transfer` plugin message (fromEntityId, toEntityId,
material/model id, qty, reason) → `sendToAll(nearbyPlayers, ...)`. Audience =
players within render distance of either endpoint.

### 3. Sim-item → MC-item map (NEW StoryMC YAML)

New config, e.g. `Story/run/plugins/Story/items.yml` loaded by `config/
ConfigService.kt` (mirror how config.yml / prompts.yml are loaded). Shape:

```yaml
# sim item id -> Minecraft rendering
items:
  bread:  { material: BREAD }
  coin:   { material: GOLD_NUGGET }
  wheat:  { material: WHEAT }
  # optional: customModelData for resource-pack item models
  potion: { material: POTION, customModelData: 1001 }
default: { material: PAPER }   # fallback for unmapped sim items
```

Expose a lookup (e.g. `ItemMapService.materialFor(simId): ItemRenderSpec`) used
when encoding the `story:item_transfer` packet. The packet should carry what the
client needs to build the `ItemStack` (namespaced material id + optional
customModelData), so the client doesn't need its own copy of the map.

---

## End-to-end flows

**Action label (client-rendered, via the existing perception popup path):**
```
behavior def BEHAVIOR.Label  (pack Lua)
sim Blackboard.active_behavior → look up BehaviorDef.label
  → entity_state_broadcast (npc.state + actionId + actionLabel)
  → WS → story-go (relay) → WS → StoryMC
  → IntentExecutor.executeNpcStateIntent (read actionLabel, diff vs last per NPC)
  → PerceptionBroadcaster.sendActionPopup(npc, label)  [story:npc_perception, PopupType.ACTION]
  → StoryClient story:npc_perception receiver (NPCMessageParserClient)
  → PerceptionPopupRenderer.onPerception(uuid, label, ACTION) → fades above head
```
Only call `sendActionPopup` when the label *changes* (diff vs last sent per NPC)
so the client isn't re-triggered every `npc.state` tick.

**Item transfer:**
```
sim trade_settle_system (item moves)
  → emit npc.item_transfer
  → WS → story-go (relay) → WS → StoryMC
  → IntentExecutor handler: resolve entities + ItemMapService lookup
  → CombatPacketBridge-style encode → story:item_transfer plugin message
  → StoryClient ItemTransferPayload receiver
  → spawn client-side item hologram, arc from→to over ~0.8s, fade out
```

---

## story-go note

story-go is a **pure relay** for these (verified: `npc.state` and
`frontend.intent` are forwarded verbatim in `internal/sim/handler.go`;
`broadcastToFrontends`). New `npc.item_transfer` likely needs adding to the
forwarded-message allowlist in story-go's sim handler / events constants
(`pkg/events/events.go`). Check `internal/sim/events.go` for the
`isSimEvent`-style filter and add the new type so it isn't dropped.

---

## Suggested build order (each independently testable)

1. **Item map config** in StoryMC (`items.yml` + `ItemMapService`, unit-test the
   lookup + fallback). No wire yet.
2. **Sim item-transfer event** from `trade_settle_system` only (one emit point),
   plus story-go passthrough. Verify with a sim test asserting the JSON shape and
   by watching the wire (BRP/logs) during a trade.
3. **StoryMC item-transfer handler → `story:item_transfer` packet** (no client
   render yet; log on receipt to confirm wire).
4. **StoryClient item hologram** receiver + renderer (the visible payoff).
5. **Action label**: author `BEHAVIOR.Label` on a couple of behaviors; add
   `label` to `BehaviorDef` + loader; put `actionLabel`/`actionId` on `npc.state`;
   StoryMC change-diffs and calls `PerceptionBroadcaster.sendActionPopup` with
   `PopupType.ACTION`; add `ACTION` to the client `PopupType` + a style in
   `PerceptionPopupRenderer`. (No new channel/renderer — reuses
   `story:npc_perception`.)
6. Backfill other transfer emit points (gift/give) once the trade path is proven.

---

## Decided

- **Both visualizations are client-side rendered.** StoryMC renders nothing
  itself (no DecentHolograms for these) — it only relays/encodes. Do not fall
  back to server-side DH.
- **Action labels reuse the existing perception-popup path** (`story:npc_perception`
  + `PerceptionPopupRenderer` + `PopupType.ACTION`). Do NOT add a `story:npc_action`
  channel or a parallel renderer. Item transfers get their own
  `story:item_transfer` channel.
- **Which behaviors show a label is authored per behavior via `BEHAVIOR.Label`**
  in the pack Lua. No label = silent. No separate denylist.

## Open decisions for the implementer to confirm with the user

- **Label copy** per behavior (the exact strings on each `BEHAVIOR.Label`), and
  whether `head_to_known_location` should be binding-aware (e.g. "Heading to the
  market" using the location_tag) vs a static "Heading somewhere".
- **Item holo style:** straight lerp vs arc; spin/bob; show qty as a count badge;
  whether `coin`/currency gets a special "coins fly" effect distinct from goods.
- **Mapping ownership:** confirm `items.yml` in StoryMC is the desired home (vs a
  shared config or sim-pack-authored mapping). User suggested StoryMC YAML.
- **Audience/perf:** cap concurrent item holos; only emit for NPCs near a player?
  (Trades happen between two NPCs with no player around — probably skip rendering
  when no player is in range, decided MC-side by the packet audience filter.)

## Key file references

- sim state broadcast: `story-sim/src/plugins/entity_state_broadcast.rs`
- sim trade settle (item move): `story-sim/src/plugins/behavior/trade/lifecycle.rs` (`trade_settle_system`)
- sim active behavior source: `Blackboard.active_behavior`, `ActiveActions` (`story-sim/src/components/`)
- sim BehaviorDef + loader (add `label`): `story-sim/src/resources/mod.rs` (`struct BehaviorDef`), `story-sim/src/plugins/definition_loader/behaviors.rs` (`parse_one`, ~line 195; `extract_optional_string` at line 68)
- **MC action-label sender (REUSE):** `Story/src/main/kotlin/com/canefe/story/perception/PerceptionBroadcaster.kt` — `story:npc_perception` channel (line ~238); add `sendActionPopup` + `PopupType.ACTION`
- MC→client packet pattern (item transfer): `Story/src/main/kotlin/com/canefe/story/combat/packet/CombatPacketBridge.kt`
- MC npc.state intake: `Story/src/main/kotlin/com/canefe/story/bridge/IntentExecutor.kt` (`executeNpcStateIntent`), DTO in `bridge/DomainEvents.kt`, decode in `bridge/WebSocketTransport.kt`, dispatch in `Story.kt`
- MC config loading (items.yml): `Story/src/main/kotlin/com/canefe/story/config/ConfigService.kt`
- **client action-label renderer (REUSE):** `StoryClient/src/client/kotlin/com/canefe/storyclient/client/perception/PerceptionPopupRenderer.kt` (`onPerception`/`render`), `perception/NpcPerceptionPayload.kt` (`PopupType` enum — add `ACTION`), registered in `client/NPCMessageParserClient.kt` (~line 169–175)
- client item-transfer (NEW) + payload pattern reference: `StoryClient/src/client/kotlin/com/canefe/storyclient/client/combat/*` (`HitOutcomePayload`), `client/perception/NpcPerceptionPayload.kt`
- story-go relay: `story-go/internal/sim/handler.go`, `internal/sim/events.go`, `pkg/events/events.go`
