# Health Tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A standalone RimWorld-style Health window in StoryClient showing the local player's hediffs grouped by body part, backed by a new `body_part` field plumbed through the sim→client pipeline.

**Architecture:** Part 1 adds `body_part` end-to-end (story-sim → story-proto → story-go → StoryMC → StoryClient); the sim's combat damage path is the only source of a real part, everything else is whole-body (`""`). Part 2 builds the StoryClient UI: a `HealthPanel` rendered inside the existing single ImGui frame (the DM render gate is relaxed so it draws outside DM mode), reading a derived `HealthState` that groups `HediffHudState.active` by part.

**Tech Stack:** Rust/Bevy (story-sim), protobuf/prost (story-proto), Go (story-go), Kotlin/Paper (StoryMC), Kotlin/Fabric 1.21.1 + imgui-java (StoryClient).

## Global Constraints

- Wire sentinel: empty string `""` = whole-body (NOT null) across proto/JSON/DTOs.
- Backward compatible: new field must default to `""` so old senders/receivers keep working.
- StoryClient compile gate: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && /Users/canefe/Projects/personal/StoryClient/gradlew -p /Users/canefe/Projects/personal/StoryClient compileClientJava compileClientKotlin -q`
- StoryMC compile gate (run inside `/Users/canefe/Projects/personal/Story`): `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin`
- story-sim build gate (run inside `/Users/canefe/Projects/personal/story-sim`): `cargo build`
- Conventional commits; no Co-Authored-By lines.
- Shell cwd resets between commands — always use absolute paths or `gradlew -p <abs>`.
- Don't touch the pause/freeze pipeline (`PauseStatePacketBridge`, `PlayerFreezeMixin`, `PauseOverlayHud`, `pause/`, `SimPaused`).
- Body type for the paper-doll v1: `baseliner/male/` only.
- Health window keybind: GLFW key **H**.

---

## Part 1 — `body_part` wire

### Task 1: story-sim — `ActiveHediff.body_part` + `Hediffs::apply` param

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-sim/src/components/hediff.rs`

**Interfaces:**
- Produces: `ActiveHediff.body_part: Option<String>`; `Hediffs::apply(&mut self, hediff_id, def, caused_by, current_time, current_stat_values, target_part: Option<String>) -> bool` (new trailing param).

- [ ] **Step 1: Add the field to `ActiveHediff`**

In `src/components/hediff.rs`, add to the `ActiveHediff` struct (after `base_stat_values`):

```rust
    pub base_stat_values: HashMap<String, f32>,
    /// Body part this hediff is localized to (e.g. "left_arm"); None = whole-body.
    pub body_part: Option<String>,
```

- [ ] **Step 2: Add `target_part` param to `apply` and store it**

Change the `apply` signature and the `ActiveHediff` construction:

```rust
    pub fn apply(
        &mut self,
        hediff_id: impl Into<String>,
        def: &HediffDef,
        caused_by: Option<String>,
        current_time: f64,
        current_stat_values: HashMap<String, f32>,
        target_part: Option<String>,
    ) -> bool {
```

In the `self.hediffs.insert(...)` body, add the field:

```rust
            base_stat_values: current_stat_values,
            body_part: target_part,
```

- [ ] **Step 3: Compile (will fail at call sites — expected)**

Run: `cd /Users/canefe/Projects/personal/story-sim && cargo build`
Expected: FAIL — `apply` called with wrong arg count in stat_threshold_system, need_threshold_system, reaction_system. Those are fixed in Tasks 2–3. Do not commit yet.

---

### Task 2: story-sim — whole-body callers pass `None`

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-sim/src/systems/stat_threshold_system.rs`
- Modify: `/Users/canefe/Projects/personal/story-sim/src/systems/need_threshold_system.rs`

**Interfaces:**
- Consumes: `Hediffs::apply(..., target_part)` from Task 1.

- [ ] **Step 1: Pass `None` in stat_threshold_system**

Find the `hediffs.apply(` call (around line 59) and add `None` as the final arg:

```rust
        let was_new = hediffs.apply(
            hediff_id.clone(),
            def,
            Some(caused_by.clone()),
            now,
            snapshot,
            None, // whole-body: stat-threshold hediffs (e.g. fainted) aren't part-localized
        );
```

- [ ] **Step 2: Pass `None` in need_threshold_system**

Find the `hediffs.apply(` call (around line 65) and add `None` as the final arg:

```rust
        let was_new = hediffs.apply(
            hediff_id.clone(),
            def,
            Some(caused_by.clone()),
            now,
            snapshot,
            None, // whole-body: need-threshold hediffs (e.g. malnutrition) aren't part-localized
        );
```

- [ ] **Step 3: Compile (still fails on reaction_system — expected)**

Run: `cd /Users/canefe/Projects/personal/story-sim && cargo build`
Expected: FAIL only in `reaction_system.rs` (`apply` call + `ApplyHediffRequest`). Fixed in Task 3.

---

### Task 3: story-sim — `ApplyHediffRequest.body_part` + combat source

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-sim/src/systems/reaction_system.rs`
- Modify: `/Users/canefe/Projects/personal/story-sim/src/systems/health_system.rs`

**Interfaces:**
- Consumes: `Hediffs::apply(..., target_part)` from Task 1.
- Produces: `ApplyHediffRequest.body_part: Option<String>`.

- [ ] **Step 1: Add `body_part` to `ApplyHediffRequest`**

In `reaction_system.rs`, find the `ApplyHediffRequest` struct and add:

```rust
    pub hediff_id: String,
    pub caused_by: Option<String>,
    /// Body part for localized injuries; None = whole-body.
    pub body_part: Option<String>,
```

- [ ] **Step 2: Lua reaction push passes `None`**

In `reaction_system.rs`, the `apply_hediff` Lua closure pushes an `ApplyHediffRequest` (around line 366). Add `body_part: None`:

```rust
        hq.lock().unwrap().push(ApplyHediffRequest {
            entity,
            hediff_id: id,
            caused_by: Some(reaction_id_c2.clone()),
            body_part: None, // Lua apply_hediff has no part arg in v1
        });
```

- [ ] **Step 3: Thread `req.body_part` into `apply` in the queue drain**

In `reaction_system.rs`, the `apply_queued_reactions_system` calls `hediffs.apply(&req.hediff_id, def, req.caused_by, current_time, stat_snapshot)` (around line 270). Add the part:

```rust
        let was_new = hediffs.apply(
            &req.hediff_id,
            def,
            req.caused_by,
            current_time,
            stat_snapshot,
            req.body_part.clone(),
        );
```

- [ ] **Step 4: Combat queue push passes the struck part**

In `health_system.rs`, the combat-damage hediff queue push (around line 260) builds an `ApplyHediffRequest` from the damage outcome. Add `body_part: outcome.part_hit.clone()` (confirm the outcome binding name in scope — it is `outcome` from `apply_damage`):

```rust
        lock.push(ApplyHediffRequest {
            entity,
            hediff_id: hediff.hediff_id.clone(),
            caused_by: hediff.caused_by.clone(),
            body_part: outcome.part_hit.clone(),
        });
```

- [ ] **Step 5: Compile clean**

Run: `cd /Users/canefe/Projects/personal/story-sim && cargo build`
Expected: PASS (warnings ok). If `outcome` is not the binding name at the push site, grep upward in `health_system.rs` for the `apply_damage(` result binding and use that name.

- [ ] **Step 6: Commit**

```bash
cd /Users/canefe/Projects/personal/story-sim && git add -A && git commit -m "feat: track body_part on ActiveHediff from combat damage"
```

---

### Task 4: story-sim — emit `body_part` in proto broadcast + proto field

**Files:**
- Modify: `/Users/canefe/Projects/personal/story-sim/proto/story/v1/events.proto`
- Modify: `/Users/canefe/Projects/personal/story-sim/src/plugins/entity_state_broadcast.rs`

**Interfaces:**
- Produces: proto `Hediff.body_part` (field 6); broadcast writes it.

- [ ] **Step 1: Add proto field 6**

In `events.proto`, the `Hediff` message — add after `description`:

```proto
  string description = 5;  // tooltip body, from HediffDef.description (may be "")
  string body_part = 6;    // localized part id, e.g. "left_arm"; "" = whole-body
```

- [ ] **Step 2: Write `body_part` in the broadcast**

In `entity_state_broadcast.rs`, the proto `Hediff { ... }` construction (around line 132) — add:

```rust
                    description: def
                        .and_then(|d| d.description.clone())
                        .unwrap_or_default(),
                    body_part: ah.body_part.clone().unwrap_or_default(),
```

- [ ] **Step 3: Compile clean**

Run: `cd /Users/canefe/Projects/personal/story-sim && cargo build`
Expected: PASS (prost regenerates the proto struct with the new field on build).

- [ ] **Step 4: Commit**

```bash
cd /Users/canefe/Projects/personal/story-sim && git add -A && git commit -m "feat: emit hediff body_part over proto"
```

---

### Task 5: story-proto — add `body_part` to canonical `Hediff`

**Files:**
- Modify: `<story-proto repo>/story/v1/events.proto` (the submodule source of truth; the StoryMC copy lives at `/Users/canefe/Projects/personal/Story/src/main/proto/story/v1/events.proto`)

**Interfaces:**
- Produces: canonical proto `Hediff.body_part = 6` matching Task 4.

- [ ] **Step 1: Locate the canonical proto**

Run: `cat /Users/canefe/Projects/personal/Story/src/main/proto/story/v1/events.proto | grep -n "message Hediff" `
Then read the `Hediff` block to confirm fields 1–5 match story-sim's copy.

- [ ] **Step 2: Add field 6 (identical to Task 4 Step 1)**

```proto
  string description = 5;
  string body_part = 6;    // localized part id, e.g. "left_arm"; "" = whole-body
```

- [ ] **Step 3: Commit + push to main**

Pushing story-proto to main is the approved workflow.

```bash
cd /Users/canefe/Projects/personal/Story/src/main/proto && git add story/v1/events.proto && git commit -m "feat: add Hediff.body_part field" && git push origin HEAD:main
```

(If the submodule is detached, push the commit to `main` per the proto workflow; the gitlink bump in StoryMC is handled in Task 7.)

---

### Task 6: story-go — regenerate proto

**Files:**
- Modify: generated proto in story-go (e.g. `gen/story/v1/events.pb.go`)

**Interfaces:**
- Consumes: story-proto `Hediff.body_part` from Task 5.
- Produces: Go struct with `BodyPart` field (forwarded unchanged — no logic edit).

- [ ] **Step 1: Sync proto + regenerate**

In the story-go repo, update its proto submodule/copy to the Task 5 commit, then run its codegen (the project's `buf generate` / `protoc` make target — check the repo's Makefile/justfile).

- [ ] **Step 2: Verify the field appears**

Run: `grep -rn "BodyPart" <story-go>/gen/story/v1/events.pb.go`
Expected: a `BodyPart string` field on the `Hediff` struct.

- [ ] **Step 3: Build + commit**

```bash
# inside story-go
go build ./... && git add -A && git commit -m "chore: regenerate proto with Hediff.body_part"
```

story-go forwards the `SimEvent` opaque-ly, so no handler change is needed — the regen alone carries the field through.

---

### Task 7: StoryMC — `HediffDto.bodyPart` + adapter + relay signature

**Files:**
- Modify: `/Users/canefe/Projects/personal/Story/src/main/kotlin/com/canefe/story/bridge/DomainEvents.kt:264-271`
- Modify: `/Users/canefe/Projects/personal/Story/src/main/kotlin/com/canefe/story/bridge/SimEventAdapter.kt` (NPC_STATE hediff map, ~line 60)
- Modify: `/Users/canefe/Projects/personal/Story/src/main/kotlin/com/canefe/story/bridge/HediffRelay.kt:77-81`
- Modify: `/Users/canefe/Projects/personal/Story/src/main/proto` (submodule gitlink bump to Task 5 commit)

**Interfaces:**
- Consumes: proto `Hediff.body_part` (Task 5).
- Produces: `HediffDto.bodyPart: String` (default `""`); relay JSON now carries `bodyPart`.

- [ ] **Step 1: Bump the proto submodule**

```bash
cd /Users/canefe/Projects/personal/Story/src/main/proto && git fetch origin && git checkout main && git pull
```

- [ ] **Step 2: Add `bodyPart` to `HediffDto`**

In `DomainEvents.kt`, the `HediffDto` data class:

```kotlin
@Serializable
data class HediffDto(
    val id: String,
    val severity: Float,
    val label: String,
    val stage: String,
    val description: String,
    val bodyPart: String = "",
)
```

- [ ] **Step 3: Map it in `SimEventAdapter`**

In `SimEventAdapter.kt`, the NPC_STATE `hediffs = s.hediffsList.map { h -> HediffDto(...) }` block — add the field (proto getter is `h.bodyPart` for `body_part`):

```kotlin
                HediffDto(
                    id = h.id,
                    severity = h.severity,
                    label = h.label,
                    stage = h.stage,
                    description = h.description,
                    bodyPart = h.bodyPart,
                )
```

- [ ] **Step 4: Include `bodyPart` in the relay change-signature**

In `HediffRelay.kt`, the `signature` companion fn:

```kotlin
        fun signature(hediffs: List<HediffDto>): String =
            hediffs
                .map { "${it.id}:${it.severity}:${it.stage}:${it.bodyPart}" }
                .sorted()
                .joinToString("|")
```

- [ ] **Step 5: Compile clean**

Run: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && cd /Users/canefe/Projects/personal/Story && ./gradlew compileKotlin`
Expected: PASS. (Proto codegen runs as part of the Gradle build; if `h.bodyPart` is unresolved, confirm the submodule bump in Step 1 took and the generated class regenerated.)

- [ ] **Step 6: Commit**

```bash
cd /Users/canefe/Projects/personal/Story && git add src/main/proto src/main/kotlin/com/canefe/story/bridge/DomainEvents.kt src/main/kotlin/com/canefe/story/bridge/SimEventAdapter.kt src/main/kotlin/com/canefe/story/bridge/HediffRelay.kt && git commit -m "feat: carry hediff bodyPart through SimEventAdapter to client relay"
```

---

### Task 8: StoryClient — `bodyPart` on `HediffDTO` + `HediffEntry` (TDD round-trip)

**Files:**
- Modify: `/Users/canefe/Projects/personal/StoryClient/src/client/kotlin/com/canefe/storyclient/client/hediff/HediffPacketReceiver.kt:41-48,71-81`
- Modify: `/Users/canefe/Projects/personal/StoryClient/src/client/kotlin/com/canefe/storyclient/client/hediff/HediffHudState.kt:112-118`
- Test: `/Users/canefe/Projects/personal/StoryClient/src/test/kotlin/.../hediff/HediffPayloadTest.kt` (existing — extend)

**Interfaces:**
- Consumes: relay JSON with `bodyPart` (Task 7).
- Produces: `HediffEntry.bodyPart: String` — consumed by `HealthState` (Task 10).

- [ ] **Step 1: Locate the existing test**

Run: `find /Users/canefe/Projects/personal/StoryClient/src/test -name "HediffPayloadTest.kt"`
Read it to match its existing assertion style and JSON helper.

- [ ] **Step 2: Add a failing round-trip assertion**

Add a test that decodes a payload carrying `bodyPart` and a payload omitting it (default `""`):

```kotlin
@Test
fun `decodes bodyPart and defaults to empty when absent`() {
    val withPart = """{"hediffs":[{"id":"cut","severity":0.3,"label":"Cut","stage":"Minor","description":"","bodyPart":"left_arm"}]}"""
    val withoutPart = """{"hediffs":[{"id":"malnutrition","severity":0.5,"label":"Malnutrition","stage":"Serious","description":""}]}"""

    val a = HediffPacketReceiver.json.decodeFromString(HediffPacketReceiver.HediffsDTO.serializer(), withPart)
    val b = HediffPacketReceiver.json.decodeFromString(HediffPacketReceiver.HediffsDTO.serializer(), withoutPart)

    assertEquals("left_arm", a.hediffs[0].bodyPart)
    assertEquals("", b.hediffs[0].bodyPart)
}
```

- [ ] **Step 3: Run — verify it fails to compile (no `bodyPart`)**

Run: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && /Users/canefe/Projects/personal/StoryClient/gradlew -p /Users/canefe/Projects/personal/StoryClient compileTestKotlin -q`
Expected: FAIL — `bodyPart` unresolved on `HediffDTO`.

- [ ] **Step 4: Add `bodyPart` to `HediffDTO`**

In `HediffPacketReceiver.kt`:

```kotlin
    @Serializable
    data class HediffDTO(
        val id: String,
        val severity: Float = 0f,
        val label: String = "",
        val stage: String = "Minor",
        val description: String = "",
        val bodyPart: String = "",
    )
```

- [ ] **Step 5: Add `bodyPart` to `HediffEntry` and thread it through**

In `HediffHudState.kt`:

```kotlin
data class HediffEntry(
    val id: String,
    val severity: Float,
    val label: String,
    val stage: String,
    val description: String,
    val bodyPart: String,
)
```

In `HediffPacketReceiver.handleInbound`, the `.map { HediffEntry(...) }`:

```kotlin
                HediffEntry(
                    id = it.id,
                    severity = it.severity,
                    label = it.label,
                    stage = it.stage,
                    description = it.description,
                    bodyPart = it.bodyPart,
                )
```

- [ ] **Step 6: Run the test — verify it passes**

Run: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && /Users/canefe/Projects/personal/StoryClient/gradlew -p /Users/canefe/Projects/personal/StoryClient test --tests "*HediffPayloadTest*" -q`
Expected: PASS. (If other constructors of `HediffEntry` exist, the compile will flag them — fix by passing `bodyPart = ""`.)

- [ ] **Step 7: Commit**

```bash
cd /Users/canefe/Projects/personal/StoryClient && git add src/client/kotlin/com/canefe/storyclient/client/hediff src/test && git commit -m "feat: decode hediff bodyPart on client"
```

---

## Part 2 — Health window (StoryClient)

### Task 9: Verify keybind H availability + MC field signatures

**Files:**
- Read only: `/Users/canefe/Projects/personal/StoryClient/src/client/kotlin/com/canefe/storyclient/client/permission/PermissionKeybinds.kt`
- Read only: `/Users/canefe/Projects/personal/StoryClient/src/client/kotlin/com/canefe/storyclient/client/NPCMessageParserClient.kt`

**Interfaces:**
- Produces: a confirmed conclusion on whether H clashes with PermissionKeybinds "deny".

- [ ] **Step 1: Inspect the existing H binding**

Run: `grep -n "GLFW_KEY_H\|deny\|DENY" /Users/canefe/Projects/personal/StoryClient/src/client/kotlin/com/canefe/storyclient/client/permission/PermissionKeybinds.kt`
Note how `deny` is gated (it drains `wasPressed()` when a screen is open). Two `KeyBinding`s on H is legal in Fabric — both receive the press; MC's controls screen lets the user rebind one. Conclusion to carry into Task 11: register Health on H; both fire, which is acceptable for v1 (Health toggles a window, deny acts on a pending permission — they rarely coincide). If it proves annoying in-game, rebind later.

- [ ] **Step 2: No commit (read-only task).**

---

### Task 10: `HealthState` — group `HediffHudState.active` by part (TDD)

**Files:**
- Create: `/Users/canefe/Projects/personal/StoryClient/src/client/kotlin/com/canefe/storyclient/client/health/HealthState.kt`
- Test: `/Users/canefe/Projects/personal/StoryClient/src/test/kotlin/com/canefe/storyclient/client/health/HealthStateTest.kt`

**Interfaces:**
- Consumes: `HediffEntry.bodyPart` (Task 8); `HediffHudState.severityFraction`.
- Produces:
  - `HealthState.wholeBody(entries: List<HediffEntry>): List<HediffEntry>` — entries with blank `bodyPart`.
  - `HealthState.byPart(entries: List<HediffEntry>): Map<String, List<HediffEntry>>` — non-blank parts grouped, insertion-ordered.
  - `HealthState.partSeverity(entries: List<HediffEntry>, part: String): Float` — max `severityFraction` among that part's entries, 0f if none.

- [ ] **Step 1: Write failing tests**

```kotlin
package com.canefe.storyclient.client.health

import com.canefe.storyclient.client.hediff.HediffEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthStateTest {
    private fun h(id: String, part: String, sev: Float = 0.5f) =
        HediffEntry(id, sev, id, "Minor", "", part)

    @Test fun `splits whole-body from localized`() {
        val list = listOf(h("malnutrition", ""), h("cut", "left_arm"))
        assertEquals(listOf("malnutrition"), HealthState.wholeBody(list).map { it.id })
        assertEquals(setOf("left_arm"), HealthState.byPart(list).keys)
        assertEquals(listOf("cut"), HealthState.byPart(list)["left_arm"]!!.map { it.id })
    }

    @Test fun `partSeverity is the max fraction on the part`() {
        val list = listOf(h("cut", "left_arm", 0.2f), h("bruise", "left_arm", 0.7f))
        assertEquals(0.7f, HealthState.partSeverity(list, "left_arm"))
        assertEquals(0f, HealthState.partSeverity(list, "head"))
    }
}
```

- [ ] **Step 2: Run — verify fail (no HealthState)**

Run: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && /Users/canefe/Projects/personal/StoryClient/gradlew -p /Users/canefe/Projects/personal/StoryClient compileTestKotlin -q`
Expected: FAIL — `HealthState` unresolved.

- [ ] **Step 3: Implement `HealthState`**

```kotlin
package com.canefe.storyclient.client.health

import com.canefe.storyclient.client.hediff.HediffEntry
import com.canefe.storyclient.client.hediff.HediffHudState

/** Derived grouping of the local player's hediffs for the Health window. Pure; no packets. */
object HealthState {
    fun wholeBody(entries: List<HediffEntry>): List<HediffEntry> =
        entries.filter { it.bodyPart.isBlank() }

    fun byPart(entries: List<HediffEntry>): Map<String, List<HediffEntry>> =
        entries.filter { it.bodyPart.isNotBlank() }.groupByTo(LinkedHashMap()) { it.bodyPart }

    fun partSeverity(entries: List<HediffEntry>, part: String): Float =
        entries.filter { it.bodyPart == part }
            .maxOfOrNull { HediffHudState.severityFraction(it) } ?: 0f

    /** Convenience snapshot from live HUD state. */
    fun snapshot(): List<HediffEntry> = HediffHudState.active
}
```

- [ ] **Step 4: Run tests — verify pass**

Run: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && /Users/canefe/Projects/personal/StoryClient/gradlew -p /Users/canefe/Projects/personal/StoryClient test --tests "*HealthStateTest*" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/canefe/Projects/personal/StoryClient && git add src/client/kotlin/com/canefe/storyclient/client/health src/test && git commit -m "feat: HealthState grouping of hediffs by body part"
```

---

### Task 11: `HealthPanel` skeleton + keybind + relaxed render gate

**Files:**
- Create: `/Users/canefe/Projects/personal/StoryClient/src/client/kotlin/com/canefe/storyclient/client/health/HealthPanel.kt`
- Modify: `/Users/canefe/Projects/personal/StoryClient/src/client/kotlin/com/canefe/storyclient/client/dm/DMPanelManager.kt:80-82,108-110` (render gate + wantsMouse/Keyboard)
- Modify: `/Users/canefe/Projects/personal/StoryClient/src/client/kotlin/com/canefe/storyclient/client/NPCMessageParserClient.kt` (keybind registration + tick toggle)

**Interfaces:**
- Consumes: `DMPanelManager.interFont`.
- Produces: `HealthPanel.isOpen(): Boolean`, `HealthPanel.toggle()`, `HealthPanel.render()`.

- [ ] **Step 1: Create the panel skeleton**

```kotlin
package com.canefe.storyclient.client.health

import com.canefe.storyclient.client.dm.DMPanelManager
import imgui.ImGui
import imgui.type.ImBoolean

/**
 * Standalone RimWorld-style Health window for the local player. Rendered inside
 * the shared ImGui frame owned by [DMPanelManager], but NOT docked into the DM
 * dockspace and NOT part of the DM panel list — it floats and opens independently
 * of DM mode via its own keybind.
 */
object HealthPanel {
    private val open = ImBoolean(false)

    fun isOpen(): Boolean = open.get()
    fun toggle() = open.set(!open.get())
    fun setOpen(value: Boolean) = open.set(value)

    fun render() {
        if (!open.get()) return
        val font = DMPanelManager.interFont
        if (font != null) ImGui.pushFont(font)
        if (ImGui.begin("Health###StoryHealth", open)) {
            ImGui.text("Health window — content lands in Task 12.")
        }
        ImGui.end()
        if (font != null) ImGui.popFont()
    }
}
```

- [ ] **Step 2: Relax the DMPanelManager render gate + render the panel**

In `DMPanelManager.render()`, change the early-return and add the Health render call after the DM `panels.forEach`:

```kotlin
    fun render() {
        if (!active && !com.canefe.storyclient.client.health.HealthPanel.isOpen()) return
        if (!initialized) init()
```

(Keep the existing import style of the file — add a top import for `HealthPanel` rather than the inline FQN; inline shown only for locating the edit.)

After the menu bar + DM panels block, before `ImGui.render()`:

```kotlin
        if (active) {
            renderMenuBar()
            panels.forEach { runCatching { it.render() } }
        }
        runCatching { HealthPanel.render() }
```

Note: the `renderMenuBar()` + `panels.forEach` are now gated on `active` so DM chrome doesn't appear when only Health is open. The dockspace call above stays — a passthrough dockspace with no docked windows is invisible.

- [ ] **Step 3: Extend input gating**

In `DMPanelManager`:

```kotlin
    fun wantsMouse(): Boolean =
        (active || HealthPanel.isOpen()) && initialized && getIO().wantCaptureMouse
    fun wantsKeyboard(): Boolean =
        (active || HealthPanel.isOpen()) && initialized && getIO().wantCaptureKeyboard
```

(Add the `HealthPanel` import.)

- [ ] **Step 4: Register the keybind + toggle on tick**

In `NPCMessageParserClient.kt`, alongside the existing `dmPanelKey` registration:

```kotlin
val healthKey =
    net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(
        net.minecraft.client.option.KeyBinding(
            "key.storyclient.health",
            org.lwjgl.glfw.GLFW.GLFW_KEY_H,
            "key.categories.storyclient",
        ),
    )
```

Inside the existing `ClientTickEvents.END_CLIENT_TICK` block, next to the DM toggle:

```kotlin
while (healthKey.wasPressed()) {
    com.canefe.storyclient.client.health.HealthPanel.toggle()
}
```

- [ ] **Step 5: Compile clean**

Run: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && /Users/canefe/Projects/personal/StoryClient/gradlew -p /Users/canefe/Projects/personal/StoryClient compileClientJava compileClientKotlin -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd /Users/canefe/Projects/personal/StoryClient && git add src/client/kotlin/com/canefe/storyclient/client/health/HealthPanel.kt src/client/kotlin/com/canefe/storyclient/client/dm/DMPanelManager.kt src/client/kotlin/com/canefe/storyclient/client/NPCMessageParserClient.kt && git commit -m "feat: standalone Health window panel + H keybind"
```

---

### Task 12: Conditions section (whole-body, bars + icons)

**Files:**
- Modify: `/Users/canefe/Projects/personal/StoryClient/src/client/kotlin/com/canefe/storyclient/client/health/HealthPanel.kt`
- Create: `/Users/canefe/Projects/personal/StoryClient/src/client/kotlin/com/canefe/storyclient/client/health/HealthIcons.kt`

**Interfaces:**
- Consumes: `HealthState.snapshot()`, `HealthState.wholeBody()`, `HediffHudState.severityFraction`.
- Produces: `HealthIcons.iconFor(id: String): Identifier` — best-effort `common/<...>.png` mapping with a fallback.

- [ ] **Step 1: Icon mapping helper**

```kotlin
package com.canefe.storyclient.client.health

import net.minecraft.util.Identifier

/** Maps a hediff id to a condition icon under textures/health/common/. */
object HealthIcons {
    private const val BASE = "textures/health/common"
    private val byId = mapOf(
        "malnutrition" to "food",
        "dehydration" to "blooddropextreme",
        "blood_loss" to "blooddropextreme",
        "infection" to "disease",
        "toxin" to "toxin",
    )
    fun iconFor(id: String): Identifier {
        val name = byId[id] ?: "whiteromb"
        return Identifier.of("storyclient", "$BASE/$name.png")
    }
}
```

- [ ] **Step 2: Render the conditions section + severity color**

Replace the placeholder body in `HealthPanel.render()` (inside the `ImGui.begin` block) with a conditions section. Use a green→yellow→red color by fraction:

```kotlin
        if (ImGui.begin("Health###StoryHealth", open)) {
            val all = HealthState.snapshot()
            val conditions = HealthState.wholeBody(all)
            ImGui.text("Conditions")
            ImGui.separator()
            if (conditions.isEmpty()) {
                ImGui.textDisabled("Healthy.")
            } else {
                for (e in conditions) {
                    val frac = com.canefe.storyclient.client.hediff.HediffHudState.severityFraction(e)
                    val col = severityColor(frac)
                    ImGui.pushStyleColor(imgui.flag.ImGuiCol.PlotHistogram, col[0], col[1], col[2], 1f)
                    ImGui.progressBar(frac, 220f, 16f, "${e.label}  ${(frac * 100).toInt()}%")
                    ImGui.popStyleColor()
                }
            }
        }
```

Add a private helper in the object:

```kotlin
    /** green (0) → yellow (.5) → red (1) RGB for a severity fraction. */
    private fun severityColor(f: Float): FloatArray {
        val c = f.coerceIn(0f, 1f)
        val r = (c * 2f).coerceAtMost(1f)
        val g = (2f - c * 2f).coerceAtMost(1f)
        return floatArrayOf(r, g, 0.15f)
    }
```

- [ ] **Step 3: Compile clean**

Run: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && /Users/canefe/Projects/personal/StoryClient/gradlew -p /Users/canefe/Projects/personal/StoryClient compileClientJava compileClientKotlin -q`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
cd /Users/canefe/Projects/personal/StoryClient && git add src/client/kotlin/com/canefe/storyclient/client/health && git commit -m "feat: Health window conditions section with severity bars"
```

---

### Task 13: Paper-doll figure (baseliner/male) + per-part injury lists

**Files:**
- Modify: `/Users/canefe/Projects/personal/StoryClient/src/client/kotlin/com/canefe/storyclient/client/health/HealthPanel.kt`
- Create: `/Users/canefe/Projects/personal/StoryClient/src/client/kotlin/com/canefe/storyclient/client/health/PaperDoll.kt`

**Interfaces:**
- Consumes: `HealthState.byPart`, `HealthState.partSeverity`, MC texture binding.
- Produces: `PaperDoll.render(injuredParts: Set<String>)` — draws layered male figure, tinting injured parts.

- [ ] **Step 1: Determine the imgui-java texture id path**

Run: `grep -rn "ImGui.image\|registerTexture\|getGlId\|TextureManager\|AbstractTexture" /Users/canefe/Projects/personal/StoryClient/src/client/kotlin | head -40`
Goal: find how (or whether) any existing code turns a Fabric `Identifier` into a GL texture id for `ImGui.image`. If none exists, the helper must bind via `MinecraftClient.getInstance().textureManager.getTexture(id)` and read its `glId` (`getGlId()` on `AbstractTexture` / `GlTexture`). Record the exact accessor found before writing Step 2.

- [ ] **Step 2: PaperDoll renderer**

Layer the male pieces in z-order (outline → torso → legs → arms → head). Resolve each `Identifier`, get its GL id via the accessor found in Step 1, and `ImGui.image`. For injured parts, draw the piece with a red tint via the `ImGui.image(texId, w, h, uv0x, uv0y, uv1x, uv1y, tintR, tintG, tintB, tintA)` overload.

```kotlin
package com.canefe.storyclient.client.health

import imgui.ImGui
import net.minecraft.client.MinecraftClient
import net.minecraft.util.Identifier

object PaperDoll {
    private const val BASE = "textures/health/baseliner/male"
    // (piece id, file) in draw order back→front.
    private val layers = listOf(
        "outline" to "outline",
        "torso" to "torso",
        "leg" to "leg",
        "upperarm" to "upperarm",
        "lowerarm" to "lowerarm",
        "hand" to "hand",
        "neck" to "neck",
        "head" to "head",
        "feet" to "feet",
    )

    private fun glId(file: String): Int {
        val id = Identifier.of("storyclient", "$BASE/$file.png")
        val tex = MinecraftClient.getInstance().textureManager.getTexture(id)
        return tex.glId // accessor confirmed in Task 13 Step 1; adjust if name differs
    }

    /** Draw the figure at the current cursor. injuredParts holds piece ids to tint red. */
    fun render(injuredParts: Set<String>, w: Float = 120f, h: Float = 220f) {
        val startX = ImGui.getCursorPosX()
        val startY = ImGui.getCursorPosY()
        for ((part, file) in layers) {
            ImGui.setCursorPos(startX, startY)
            val tint = if (part in injuredParts) floatArrayOf(1f, 0.4f, 0.4f, 1f) else floatArrayOf(1f, 1f, 1f, 1f)
            ImGui.image(glId(file), w, h, 0f, 0f, 1f, 1f, tint[0], tint[1], tint[2], tint[3])
        }
        ImGui.setCursorPos(startX, startY + h)
    }
}
```

- [ ] **Step 3: Two-column layout + per-part lists in HealthPanel**

In `HealthPanel.render()`, wrap content in two columns: left = paper-doll, right = conditions (Task 12) then per-part lists. Compute injured piece ids from `HealthState.byPart` keys (map wire part ids like `left_arm`/`right_arm` to the doll's single `upperarm`/`lowerarm`/`hand` pieces — for v1, tint `torso`/`head`/`leg`/`upperarm`/`lowerarm`/`hand`/`feet`/`neck` if any matching part is injured; unknown parts fall through harmlessly).

```kotlin
            val all = HealthState.snapshot()
            val parts = HealthState.byPart(all)
            val injured = parts.keys.mapNotNull { dollPieceFor(it) }.toSet()

            ImGui.columns(2, "health_cols", false)
            ImGui.setColumnWidth(0, 140f)
            PaperDoll.render(injured)
            ImGui.nextColumn()

            // conditions (from Task 12) ...

            if (parts.isNotEmpty()) {
                ImGui.separator()
                ImGui.text("Injuries")
                for ((part, list) in parts) {
                    ImGui.textDisabled(prettyPart(part))
                    for (e in list) {
                        val frac = com.canefe.storyclient.client.hediff.HediffHudState.severityFraction(e)
                        ImGui.bulletText("${e.label}  ${(frac * 100).toInt()}%")
                    }
                }
            }
            ImGui.columns(1)
```

Add helpers:

```kotlin
    /** Map a wire body-part id to a male-doll piece to tint; null = no visible piece. */
    private fun dollPieceFor(part: String): String? = when (part) {
        "head" -> "head"
        "neck" -> "neck"
        "torso", "chest" -> "torso"
        "left_arm", "right_arm" -> "upperarm"
        "left_hand", "right_hand" -> "hand"
        "left_leg", "right_leg" -> "leg"
        "left_foot", "right_foot" -> "feet"
        else -> null
    }

    private fun prettyPart(part: String): String =
        part.split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
```

- [ ] **Step 4: Compile clean**

Run: `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && /Users/canefe/Projects/personal/StoryClient/gradlew -p /Users/canefe/Projects/personal/StoryClient compileClientJava compileClientKotlin -q`
Expected: PASS. If `tex.glId` is the wrong accessor, fix per Task 13 Step 1's recorded name.

- [ ] **Step 5: Commit**

```bash
cd /Users/canefe/Projects/personal/StoryClient && git add src/client/kotlin/com/canefe/storyclient/client/health && git commit -m "feat: Health window paper-doll figure + per-part injury lists"
```

---

### Task 14: In-game verification

**Files:** none (manual).

- [ ] **Step 1: Launch + open**

Launch StoryClient against a running StoryMC + story-go + story-sim. Press **H** — the Health window opens with DM mode off. Confirm it renders over the world and the male figure + "Conditions" appear.

- [ ] **Step 2: Whole-body condition**

Drive the player's hunger to 0 in the sim (malnutrition). Confirm a "Conditions" bar appears, colored by severity, with the food icon path resolving (or `whiteromb` fallback).

- [ ] **Step 3: Localized injury**

Apply combat damage to the player (a Lua `damage` with a `target_part`, e.g. `left_arm`). Confirm: (a) an "Injuries → Left Arm" group appears, (b) the corresponding doll piece tints red.

- [ ] **Step 4: Input gating reality check**

With the Health window open and focused, click/drag inside it. Note whether the player also turns/moves (the known `wantsMouse/wantsKeyboard`-has-no-callers gap). If it leaks, file a follow-up to consult `DMPanelManager.wantsMouse/wantsKeyboard` from the mouse/keyboard mixins — out of scope for this plan, but record the observed behavior.

- [ ] **Step 5: No regressions**

Toggle DM mode (J) on/off with Health open and closed; confirm DM panels still appear only in DM mode and the pause overlay still works.

---

## Self-Review Notes

- **Spec coverage:** wire (Tasks 1–8) ↔ spec Part 1 §all; window plumbing (Task 11) ↔ spec Part 2 "Plumbing"; conditions (Task 12), paper-doll + per-part (Task 13) ↔ spec "Layout"; keybind H (Task 9/11); don't-break + verify (Task 14). Body-type male, `""` sentinel, gate-relax all carried into Global Constraints.
- **Deferred-by-design:** Lua `apply_hediff` part arg, multi-body-type figures, and mouse/keyboard mixin gating are explicitly out of scope (noted in Tasks 3, 13, 14).
- **Type consistency:** `HediffEntry.bodyPart: String` (Task 8) is consumed as `String` everywhere; `HealthState.byPart`/`wholeBody`/`partSeverity` signatures match between Task 10 definition and Task 12/13 use; `HealthPanel.isOpen/toggle/render` match between Task 11 and DMPanelManager use.
- **Known soft spots flagged inline, not hidden:** the imgui texture-id accessor (`tex.glId`) and the combat `outcome` binding name are confirmed at their respective steps rather than assumed.
