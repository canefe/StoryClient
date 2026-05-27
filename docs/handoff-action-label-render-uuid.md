# Handoff: Sim action labels — fixed end-to-end EXCEPT the client can't find the entity

## TL;DR

Action labels now flow correctly **sim → story-go → StoryMC → client** and the
client **receives** them (`type=ACTION label='Looking around'`). The ONLY
remaining bug: the client renderer's `findEntity` returns null for every action
popup — **`[DBG action-render] total=18 notFound=18 outOfRange=0 drawn=0`** — so
nothing draws. The UUID StoryMC sends for an action label does not match any
in-world `LivingEntity.uuid` the client can resolve, even though we send
`npc.clientFacingUuid` (the disguise UUID), the same field the perception path
uses.

**User's framing of the fix (follow this):** the sim/story-go send a *characterId*
("sophia_2189d655"). It is StoryMC's job to map that characterId → the in-world
entity UUID the client renders. The **perception popup path already does this
correctly** (👁 sightings render). So: **make the action-label path resolve the
renderable UUID by doing EXACTLY what the perception path does — don't invent a
parallel resolution.**

The next step is a 3-line diagnostic already in the client (uncommitted) that will
settle whether the disguise UUID is fundamentally unfindable or the action loop
has a bug. **Relaunch the client and read `[DBG perception-render]` vs
`[DBG action-render]` in the same frames.** See "Immediate next step".

## What works now (verified on the wire)

1. **Sim emits labels.** `entity_state_broadcast.rs` sends `actionId`/`actionLabel`
   (camelCase, matches the MC DTO). Idle behaviors now have labels
   (`idle_stand` → "Idling", `idle_look_around` → "Looking around"), and
   `head_to_known_location` → "Heading to market_square". Verified:
   `[DBG actionLabel] ... -> action_id='idle_stand' label='Idling'`.
2. **MC receives + forwards.** `executeNpcStateIntent` fires every 2s for ~21
   NPCs. `[DBG npcState] LABEL ... raw='Looking around' toSend='Looking around'`.
3. **MC ignores empty labels** (no flicker). The sim's action slots go EMPTY for
   12–18s between idle bursts (cooldowns: idle_stand=25s, idle_look_around=20s).
   `IntentExecutor.actionLabelToSend()` returns null for empty so the sticky
   client label is never cleared by inter-action gaps. 7 unit tests pass
   (`ActionLabelDiffTest`).
4. **Client receives ACTION packets.** `[DBG perception-recv] ... type=ACTION
   label='Looking around'` — confirmed arriving, non-empty, post-relaunch.

It rendered **once** (garbled — see "Already fixed: overprint") before the
render refactor; since the refactor it draws nothing because of the findEntity
miss.

## THE BUG (where to focus)

`StoryClient/.../perception/PerceptionPopupRenderer.kt` — `findEntity` matches
`it is LivingEntity && it.uuid == uuid`. For action popups this is the disguise
UUID StoryMC sent (e.g. sophia → `418aabfb-…`). The render loop reports
`notFound=18` for ALL action popups.

Contradiction to resolve: two UUIDs (`418aabfb` sophia, `c90553cb` froth) appear
in BOTH the received PERCEPTION set and the ACTION set. PERCEPTION renders (per
the user), ACTION does not, with the *same* `findEntity` and the *same* UUID.
That should be impossible — which means one of:
  - (a) The PERCEPTION popups that actually render are only the player / non-
    disguised (Citizens) NPCs, whose real entity uuid == the sent uuid. The
    disguised MythicMob NPCs' perception popups ALSO fail to find an entity
    (disguise UUID ≠ any entity's real uuid) — we just never noticed because
    perception is momentary. → The fix is about resolving the disguise entity,
    and it must be applied to BOTH paths (or the shared findEntity).
  - (b) My action render loop has a subtle bug PERCEPTION doesn't. (Less likely;
    they now share `drawPopup` and `findEntity`.)

Key fact about LibsDisguises: a `PlayerDisguise` UUID is a fake player-profile
UUID; it is NOT the disguised entity's real `entity.uuid`. So
`world.getOtherEntities { it.uuid == disguiseUuid }` will never match — the
disguised mob still has its real (mob) uuid in the world. This strongly favors
(a). If so, the renderable identity is NOT the disguise UUID; you must resolve
the actual client-side entity another way (entity id, or the recognition/nearby
cache the HelixNametag renderer uses).

## Immediate next step (settles a vs b in one relaunch)

A diagnostic is already in `PerceptionPopupRenderer.render()` (uncommitted):
  - `[DBG action-render] total=N notFound=N outOfRange=N drawn=N`
  - `[DBG perception-render] total=N notFound=N drawn=N`

1. Relaunch the client (`cd StoryClient && ./gradlew runClient`). MC + sim are
   already running with the right builds; no restart needed.
2. Stand near the disguised NPCs for ~10s.
3. Read `StoryClient/run/logs/latest.log`:
   - If `perception-render notFound>0` on the same frames action does → **(a)**:
     disguise UUID is unfindable; fix the entity resolution for both paths.
   - If `perception-render drawn>0` while `action-render notFound=18` → **(b)**:
     bug is specific to the action loop; diff the two loops.

## How the perception path resolves the UUID (the reference to copy)

`Story/.../perception/PerceptionBroadcaster.kt` tick (~line 126, 177):
```kotlin
for (npc in plugin.npcRegistry.all()) {
    val perceiverEntity = npc.entity as? LivingEntity ?: continue
    val perceiverCharId = plugin.characterRegistry.getCharacterIdForNPC(npc) ?: continue
    ...
    val clientUuid = npc.clientFacingUuid ?: perceiverEntity.uniqueId
    broadcastPerceptionPopup(clientUuid, targetName, PopupType.PERCEPTION)
}
```
- `npc` comes from `npcRegistry.all()`; for MythicMob NPCs that's a
  `MythicMobStoryNPC` whose `clientFacingUuid = disguiseUuid ?: backingEntity.uniqueId`
  (`MythicMobStoryNPC.kt:56`).
- The action path (`IntentExecutor.executeNpcStateIntent`) currently uses
  `resolveNPC(plugin, intent.characterId).clientFacingUuid` and logs the SAME
  `418aabfb` for sophia — so MC is already sending the disguise UUID, same as
  perception. (This is why the contradiction matters — the wire UUID matches.)

If diagnostic says (a): the real question is **how the client maps a sent UUID to
a renderable entity for disguised NPCs**. Look at what already works on disguised
NPCs client-side:
  - `BubbleRenderer.findEntity` (NPC dialogue bubbles render on disguised NPCs)
    matches `it.uuidAsString == state.npcId` — SAME approach. So either dialogue
    also only works because the server sends a findable id, or the disguise uuid
    IS findable in some setups. Verify what `npcId` BubbleRenderer receives vs the
    disguise uuid.
  - `HelixNametagRenderer` uses `NearbyNPCCache.get(entity.uuid)` keyed by the
    client entity's own uuid → implies the client iterates real entities and
    looks them up, rather than being handed a uuid to find.
  - `NearbyNPCBroadcaster` (server) sends `npc.clientFacingUuid` to populate the
    client's NearbyNPCCache — so the disguise uuid IS the client-facing identity
    for the nearby/recognition system. If the action popup uuid matches a
    NearbyNPCCache entry but not a world entity, the renderer may need to resolve
    via the cache → entity id, like Helix does.

Net: **make the action popup find its entity the way the working renderers do.**
Don't trust that `it.uuid == disguiseUuid` matches a world entity — verify.

## Already fixed this session (keep these)

- **Sticky ACTION popups** (`PerceptionPopupRenderer`): ACTION held in a separate
  `ConcurrentHashMap<UUID, Popup>` (one per NPC), never time-expired, alpha=1
  after rise. Fixes the original "fades in 1.8s" + the overprint/garble race
  (was sharing a non-thread-safe ArrayDeque mutated from the netty thread).
  Transient perception/combat popups keep the fade queue and stack above.
- **MC empty-clear suppression** (`actionLabelToSend`): never forward empty →
  no flicker from idle cooldown gaps. 7 tests in `ActionLabelDiffTest`.
- **RC2 join-replay** (`PerceptionBroadcaster`): `activeActionLabels` map +
  `onPlayerJoin` replays current sticky labels to a joining player (per-player
  `sendPerceptionPopupTo`). Keyed by the sent UUID — will be correct once the
  UUID resolution is fixed.
- **Sim dynamic offer discovery** (`buy_item.lua` + `execution.rs`
  `nearest_vendor_any_offer`): removed the hardcoded `tobin_bread_for_coin`;
  buy_item now finds any co-located vendor's offer. (See "Still open" — buyers
  still don't co-locate, so it hasn't fired live yet.)
- **Idle labels** (`idle_stand.lua`, `idle_look_around.lua`): so NPCs aren't
  label-less while idling.

## Still open (separate, paused)

`buy_item` is selected in the blackboard (~128×) but never occupies an action
slot: buyers never co-locate with the one vendor (only "Gaius" has an offer in
the live world; "Baker Tobin" from `spawn_village_demo.lua` didn't register an
offer this run). The dynamic-offer fix removed the hardcoded-id bug, but there's
a travel-to-vendor gap before the trade can fire. Investigate after labels render.
Evidence: sim log `[DBG actionLabel]` shows `bbBehavior=buy_item` with
`slots=[idle_*]` — the planner wants buy_item but execution leaves it in idle.

## Debug logs to remove once labels render (ALL temporary)

- Story `IntentExecutor.kt`: `[DBG npcState] ENTER/LABEL/resolveNPC NULL/entity NULL`.
- Story `entity_state_broadcast.rs` is in story-sim, not Story — see below.
- story-sim `entity_state_broadcast.rs`: the `[DBG actionLabel]` info! block.
- StoryClient `NPCMessageParserClient.kt`: `[DBG perception-recv]` println.
- StoryClient `PerceptionPopupRenderer.kt`: `[DBG action-render]` /
  `[DBG perception-render]` printlns + `lastActionDbgMs` / `lastPerceptionDbgMs`
  fields + the per-loop counter vars.

## Build / run

- MC plugin: `cd Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin` (paperweight `runServer` injects the dev jar at launch; log: `Story/run/logs/latest.log`). Version string shows the HEAD commit hash, NOT working-copy state — do not infer "old build" from it.
- Client: `cd StoryClient && export JAVA_HOME=… && ./gradlew runClient` (user runs). Log: `StoryClient/run/logs/latest.log`.
- sim: `cd story-sim && rm -f /tmp/story-sim.log && cargo run > /tmp/story-sim.log 2>&1` (background). NATS subject `story-sim.events` → story-go (`sf-orchestrator-dev-1`:8080) → MC over WS. Both NATS (`sf-nats-1`) and the orchestrator run in Docker (`docker ps`).

## Uncommitted state (jj working copies, all per-repo)

- **Story**: `IntentExecutor.kt`, `PerceptionBroadcaster.kt`, `ActionLabelDiffTest.kt`
- **StoryClient**: `NPCMessageParserClient.kt`, `PerceptionPopupRenderer.kt`, this doc
- **story-sim**: `buy_item.lua`, `idle_look_around.lua`, `idle_stand.lua`,
  `execution.rs`, `entity_state_broadcast.rs` (+ `humanlike.lua` — incidental)

Nothing committed; reviewer can squash per-repo once labels render.
