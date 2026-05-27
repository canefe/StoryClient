# Handoff: Sim action labels never appear in-world (item-transfer holograms TBD)

## TL;DR

The sim **action-label** feature is fully wired across all 4 repos and the
**server side provably works** — but the labels **never render on the client**.
Root cause is a **lifecycle/cardinality mismatch**, not a broken pipe: the label
is sent **once, on change**, and the client fades it in ~1.8s like a momentary
perception popup. An action that lasts many seconds therefore produces at most a
single ~1.8s flash — and in practice the demo NPCs almost never run a *labeled*
behavior, so essentially nothing is ever sent.

**The fix is client-side (chosen with the user): ACTION popups must be sticky —
no auto-fade — and clear only on an explicit empty-label packet.** Plus a
server-side gap to close: the change-diff is global, so a newly-connected/relogged
client never receives the current label.

This handoff is for finishing the action-label fix and then verifying the
item-transfer holograms (never visually confirmed yet).

## Evidence (how we know where it breaks)

A temporary debug log was added to **MC** `IntentExecutor.executeNpcStateIntent`
(`Story/src/main/kotlin/com/canefe/story/bridge/IntentExecutor.kt`, marked
`[DBG actionLabel]`) and to the **client** receiver
(`StoryClient/.../client/NPCMessageParserClient.kt`, marked `[DBG perception-recv]`).
**Both are still in the code — remove them after the fix is verified.**

Server log (`Story/run/logs/latest.log`) over a ~2-min window showed:

```
actionId distribution: 50 empty · 9 idle_stand · 6 idle_look_around · 4 travel_to_known_location
willSend=true total: 5   (4 of them label='' clears, exactly 1 real: 'Heading to temple_grounds')
```

A real binding-aware label **did** flow sim→go→MC and `sendActionPopup` **was**
called:
```
[DBG actionLabel] cid=caf71d8d-… actionId=travel_to_known_location
  rawLabel=Heading to temple_grounds -> label='Heading to temple_grounds'
  uuid=91783053-… willSend=true
```

So: **sim emits the label correctly, MC receives and forwards it correctly.** The
break is purely how the **client treats `PopupType.ACTION`** (lifecycle), plus how
**few** packets get sent (once-on-change).

User confirmed: **perception popups render fine; the ACTION ones never appeared
at all** (not even a flash) — consistent with "only 1 renderable packet in 2 min,
fading in 1.8s, easy to never witness."

## Root causes (two, both must be addressed)

### RC1 — Client: ACTION popups fade like momentary events (PRIMARY)

`StoryClient/src/client/kotlin/com/canefe/storyclient/client/perception/PerceptionPopupRenderer.kt`

- `render()` prunes any popup older than `TOTAL_MS` (=1800ms):
  `while (queue.isNotEmpty() && now - queue.first().startMs > TOTAL_MS) queue.removeFirst()`
- `animate(elapsed)` runs RISE→HOLD→EXIT, dropping alpha to 0 after
  `RISE_MS+HOLD_MS+EXIT_MS`.

This is correct for perception/combat (momentary), **wrong for an action label**
(a persistent state indicator). A `head_to_known_location` lasts ~10–30s but the
label vanishes after 1.8s.

**Chosen fix (by the user): ACTION popups are sticky.**
- In the prune loop, **do not** expire `PopupType.ACTION` entries by `TOTAL_MS` —
  they persist until replaced/cleared.
- In `animate`, for ACTION use the RISE phase then **hold at alpha=1 forever**
  (never enter the EXIT/fade-out branch).
- Clearing already works: `onPerception(uuid, "", ACTION)` removes the NPC's
  ACTION popup (already implemented, lines ~54–59). MC sends an empty label when
  the behavior goes silent/idle, which is the explicit clear.

Implementation sketch (the renderer is an `object`; `Popup(label, type, startMs)`):
- Prune: `while (q.isNotEmpty() && q.first().type != PopupType.ACTION && now - q.first().startMs > TOTAL_MS) q.removeFirst()`
  — but note ACTION popups are kept in their own queue position; simplest is to
  filter the prune to non-ACTION, and rely on the `removeAll { it.type == ACTION }`
  in `onPerception` for ACTION removal. Watch the stacking/order with mixed types.
- `animate`: add an `isAction` param (or a separate `animateSticky`) that returns
  `Pair(0.0, 1f)` once past RISE_MS instead of fading.
- Consider giving ACTION its own render position (e.g. slightly higher or its own
  slot) so a sticky label doesn't fight transient perception popups in the stack.

### RC2 — Server: change-diff is global, not per-client-session (SECONDARY)

`Story/src/main/kotlin/com/canefe/story/bridge/IntentExecutor.kt`
`shouldSendActionLabel(characterId, label)` keys a single global
`ConcurrentHashMap<characterId,label>`. Once "Heading to temple_grounds" is sent,
it is **never re-sent** while unchanged — so:
- A player who joins/relogs **after** the label was set never receives it.
- After the 1.8s fade (pre-fix) the label was gone with no re-send.

With RC1's sticky client, a single send mostly suffices **for clients present at
send time**, but late-joiners still get nothing. Options:
- (a) On player join, replay current labels for nearby NPCs (clear the diff cache
  for that send, or send directly to the joining player).
- (b) Periodic keepalive re-send every few seconds (the user previously declined
  the "every tick" option; a low-rate keepalive is the middle ground).
- Minimum viable: at least make the diff not permanently suppress — e.g. re-send
  on a coarse interval, or send to newly-tracked players.

Decide with the user how robust to make RC2; RC1 is the must-fix for the demo.

## Also worth flagging (not a bug, but why it looks dead)

The demo NPCs spend almost all their time **idle** (`actionId` empty / `idle_stand`
/ `idle_look_around` — all correctly silent). Only `travel_to_known_location`
fired in the sample. Even with RC1 fixed, labels will be sparse until NPCs run
more labeled behaviors (buy_item, request_alms, eat_bread…). This is a
scenario/content observation — confirm the demo actually drives those behaviors,
or labels will still look rare. (See server log `actionId distribution` above.)

## Verify the client decode path while you're at it

"Never flashing at all" leaves a small chance the client never *decodes* `type=ACTION`
(e.g. running build lacks `PopupType.ACTION(5)`). The `[DBG perception-recv]`
println in `NPCMessageParserClient.kt` was added for exactly this — relaunch the
Fabric client, get near a traveling NPC, and confirm a line like
`[DBG perception-recv] uuid=… type=ACTION label='Heading to …'` appears in the
client log/stdout. If it shows `type=PERCEPTION` for a byte-5 packet, the running
client predates the enum change → rebuild/relaunch the client. If it shows
`type=ACTION` but nothing renders → it's purely RC1.

## State of the code (what's done)

All implemented and committed via `jj` (per-task commits) across:
- **story-sim**: `BehaviorDef.label` + loader; `actionId`/`actionLabel` on
  `npc.state` (`entity_state_broadcast.rs`, `resolve_label`, dynamic-over-static);
  `BEHAVIOR.Label` authored on 9 behaviors; binding-aware
  `char.action.set("label", "Heading to "..instance_name)` in
  `head_to_known_location.lua`. **Verified working on the wire.**
- **story-go**: `NpcItemTransfer` const (passthrough relay, no logic needed).
- **Story (MC)**: `NpcStateIntent.actionId/actionLabel`; `PopupType.ACTION(5)` +
  `PerceptionBroadcaster.sendActionPopup`; `IntentExecutor.shouldSendActionLabel`
  change-diff → `sendActionPopup`. **Verified calling sendActionPopup.**
  Plus item-transfer: `ItemMapService`+`items.yml`, `NpcItemTransferIntent`,
  `ItemTransferPacketBridge`, `executeItemTransferIntent`.
- **StoryClient**: `PopupType.ACTION(5)` + style + empty-clear in
  `PerceptionPopupRenderer`; `ItemTransferPayload` + `ItemHologramRenderer`
  (item-transfer holograms — **never visually confirmed**).

Spec: `Story/docs/superpowers/specs/2026-05-23-sim-action-item-visualization-design.md`
Plan: `Story/docs/superpowers/plans/2026-05-23-sim-action-item-visualization.md`
Wire-contract memory: `~/.claude/.../memory/project_sim_visualization_wire.md`

## Your task

1. **Remove the two `[DBG …]` log statements** once you've confirmed the decode
   path (or keep them until the fix is verified, then remove).
2. **RC1 (must):** make `PopupType.ACTION` popups sticky in
   `PerceptionPopupRenderer` (no `TOTAL_MS` prune, hold alpha=1 after RISE; clear
   only via the empty-label packet). Build the client, relaunch, walk up to a
   traveling NPC, confirm a persistent "Heading to <place>" label that clears when
   the NPC stops.
3. **RC2 (decide with user):** ensure late-joining/relogged clients get current
   labels (join replay or low-rate keepalive). Don't go full per-tick.
4. **Then verify item-transfer holograms** (Phase 4 of the plan) — trigger a trade
   (the `trade_settle_system`/village demo), confirm a bread/coin item arcs
   giver→receiver. The `story:item_transfer` path was built but never seen live;
   if it doesn't show, instrument `executeItemTransferIntent`
   (`Story/.../bridge/IntentExecutor.kt`) and the client `ItemTransferPayload`
   receiver the same way the perception path was instrumented here.

## Build/run quick ref

- MC plugin: `cd Story && export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew build -x test` (run-task relaunches the Paper server; log at `Story/run/logs/latest.log`).
- Client: `cd StoryClient && export JAVA_HOME=… && ./gradlew runClient` (Fabric dev launch; this is what the user runs).
- sim: `cd story-sim && cargo run` (or the debug-binary→logfile workflow).
- Bridge must be connected (server log: "WebSocket transport connected"). Sim
  drops the connection on its own restarts — re-check it's up.
