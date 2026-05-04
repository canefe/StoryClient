# Decision System — StoryClient Fabric Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render the decision system UI in the Fabric client — receive `decision.prompt` / `decision.observe` packets from Story.kt, show a cinematic full-screen or ambient HUD, and send the player's choice back as a `decision.response` packet.

**Architecture:** A `DecisionState` singleton holds current decision data. A `DecisionPacketReceiver` registers the plugin message channel and parses inbound packets. A `DecisionHud` renders the full-screen or ambient panel via `HudRenderCallback`. A `CinematicCameraController` temporarily overrides the camera during critical decisions. All wired into `NPCMessageParserClient.onInitializeClient()`.

**Tech Stack:** Kotlin, Fabric API (`ClientPlayNetworking`, `HudRenderCallback`, `ClientTickEvents`), Minecraft `DrawContext`, `MinecraftClient.cameraEntity` for camera override.

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `src/client/kotlin/com/canefe/storyclient/client/decision/DecisionState.kt` | Holds current decision data, vote state, timeout tracking |
| Create | `src/client/kotlin/com/canefe/storyclient/client/decision/DecisionPacketReceiver.kt` | Registers plugin message channels, parses inbound packets, sends responses |
| Create | `src/client/kotlin/com/canefe/storyclient/client/decision/DecisionHud.kt` | Renders full-screen takeover (critical) or ambient HUD panel (ambient) |
| Create | `src/client/kotlin/com/canefe/storyclient/client/decision/CinematicCameraController.kt` | Sequences camera through NPC faces and top-down view during critical decisions |
| Modify | `src/client/kotlin/com/canefe/storyclient/client/NPCMessageParserClient.kt` | Register DecisionPacketReceiver and wire HUD/camera tick callbacks |

---

## Task 1: DecisionState

**Files:**
- Create: `src/client/kotlin/com/canefe/storyclient/client/decision/DecisionState.kt`

- [ ] **Step 1: Create DecisionState.kt**

```kotlin
package com.canefe.storyclient.client.decision

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class NpcVoice(
    val characterId: String,
    val name: String,
    val opinion: String,
    val stance: String,
)

@Serializable
data class DecisionOption(
    val id: String,
    val label: String,
    val consequenceHint: String = "",
)

@Serializable
data class DecisionPrompt(
    val decisionId: String,
    val mode: String,
    val leaderId: String = "",
    val playerTargets: List<String> = emptyList(),
    val title: String,
    val context: String,
    val urgency: String,
    val npcVoices: List<NpcVoice> = emptyList(),
    val options: List<DecisionOption> = emptyList(),
    val allowFreeform: Boolean = true,
    val timeoutSeconds: Int = 60,
)

@Serializable
data class DecisionObserve(
    val decisionId: String,
    val leaderName: String,
    val options: List<DecisionOption> = emptyList(),
)

object DecisionState {
    val json = Json { ignoreUnknownKeys = true }

    var activePrompt: DecisionPrompt? = null
        private set
    var activeObserve: DecisionObserve? = null
        private set

    // Ticks remaining on the countdown (set from timeoutSeconds * 20)
    var ticksRemaining: Int = 0
        private set

    // Which NPC voice is currently highlighted by the cinematic camera (index into npcVoices)
    var highlightedVoiceIndex: Int = 0
        private set

    // Votes cast so far in vote mode: characterId → choiceId
    val votes: MutableMap<String, String> = mutableMapOf()

    // Freeform input text (when player is typing a custom response)
    var freeformMode: Boolean = false
    var freeformInput: String = ""

    fun showPrompt(prompt: DecisionPrompt) {
        activePrompt = prompt
        activeObserve = null
        ticksRemaining = prompt.timeoutSeconds * 20
        highlightedVoiceIndex = 0
        votes.clear()
        freeformMode = false
        freeformInput = ""
    }

    fun showObserve(observe: DecisionObserve) {
        activeObserve = observe
        activePrompt = null
    }

    fun dismiss() {
        activePrompt = null
        activeObserve = null
        freeformMode = false
        freeformInput = ""
    }

    /** Called each tick to advance the countdown and camera cycling. */
    fun tick() {
        val prompt = activePrompt ?: return
        if (ticksRemaining > 0) ticksRemaining--

        // Cycle highlighted voice: each voice gets ~3 seconds (60 ticks), then top-down (100 ticks)
        val voiceCount = prompt.npcVoices.size
        if (voiceCount > 0) {
            val cycleLength = voiceCount * 60 + 100
            val elapsed = (prompt.timeoutSeconds * 20) - ticksRemaining
            val position = elapsed % cycleLength
            highlightedVoiceIndex = when {
                position < voiceCount * 60 -> position / 60
                else -> -1 // -1 = top-down phase
            }
        }
    }

    val isCritical: Boolean get() = activePrompt?.urgency == "critical"
    val isVisible: Boolean get() = activePrompt != null || activeObserve != null
    val isTimedOut: Boolean get() = ticksRemaining <= 0 && activePrompt != null
}
```

- [ ] **Step 2: Compile**

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/client/kotlin/com/canefe/storyclient/client/decision/DecisionState.kt
git commit -m "feat: DecisionState — decision data model and countdown tracking"
```

---

## Task 2: DecisionPacketReceiver

**Files:**
- Create: `src/client/kotlin/com/canefe/storyclient/client/decision/DecisionPacketReceiver.kt`

- [ ] **Step 1: Create DecisionPacketReceiver.kt**

```kotlin
package com.canefe.storyclient.client.decision

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import kotlinx.serialization.encodeToString

object DecisionPacketReceiver {

    // Server → Client: decision prompt or observe payload
    data class DecisionS2CPayload(val json: String) : CustomPayload {
        companion object {
            val ID = CustomPayload.Id<DecisionS2CPayload>(Identifier.of("story", "decision"))
            val CODEC = PacketCodec.of<PacketByteBuf, DecisionS2CPayload>(
                { value, buf -> buf.writeString(value.json) },
                { buf -> DecisionS2CPayload(buf.readString()) }
            )
        }
        override fun getId() = ID
    }

    // Client → Server: decision response payload
    data class DecisionC2SPayload(val json: String) : CustomPayload {
        companion object {
            val ID = CustomPayload.Id<DecisionC2SPayload>(Identifier.of("story", "decision_response"))
            val CODEC = PacketCodec.of<PacketByteBuf, DecisionC2SPayload>(
                { value, buf -> buf.writeString(value.json) },
                { buf -> DecisionC2SPayload(buf.readString()) }
            )
        }
        override fun getId() = ID
    }

    fun register() {
        // Register inbound payload type
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C()
            .register(DecisionS2CPayload.ID, DecisionS2CPayload.CODEC)

        // Register outbound payload type
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S()
            .register(DecisionC2SPayload.ID, DecisionC2SPayload.CODEC)

        ClientPlayNetworking.registerGlobalReceiver(DecisionS2CPayload.ID) { payload, context ->
            context.client().execute {
                handleInbound(payload.json)
            }
        }
    }

    private fun handleInbound(json: String) {
        // Try to parse as DecisionPrompt first (has "urgency" field), then DecisionObserve
        runCatching {
            val prompt = DecisionState.json.decodeFromString<DecisionPrompt>(json)
            // DecisionPrompt has "urgency"; DecisionObserve has "leaderName" — distinguish by field presence
            if (json.contains("\"urgency\"")) {
                DecisionState.showPrompt(prompt)
                if (DecisionState.isCritical) {
                    CinematicCameraController.start(prompt)
                }
                return
            }
        }
        runCatching {
            val observe = DecisionState.json.decodeFromString<DecisionObserve>(json)
            DecisionState.showObserve(observe)
        }.onFailure {
            println("[DecisionPacketReceiver] Failed to parse inbound packet: ${it.message}")
        }
    }

    fun sendResponse(decisionId: String, choiceId: String?, freeformText: String?) {
        @kotlinx.serialization.Serializable
        data class ResponsePayload(
            val type: String = "decision.response",
            val decisionId: String,
            val choiceId: String?,
            val freeformText: String?,
        )

        val json = DecisionState.json.encodeToString(
            ResponsePayload(decisionId = decisionId, choiceId = choiceId, freeformText = freeformText)
        )
        ClientPlayNetworking.send(DecisionC2SPayload(json))

        DecisionState.dismiss()
        CinematicCameraController.stop()
    }
}
```

- [ ] **Step 2: Compile**

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/client/kotlin/com/canefe/storyclient/client/decision/DecisionPacketReceiver.kt
git commit -m "feat: DecisionPacketReceiver — plugin message channel registration and response sending"
```

---

## Task 3: CinematicCameraController

**Files:**
- Create: `src/client/kotlin/com/canefe/storyclient/client/decision/CinematicCameraController.kt`

The cinematic camera works by spawning a fake invisible entity at each NPC's position and temporarily setting it as `MinecraftClient.cameraEntity`. On each stop it restores the original player entity.

- [ ] **Step 1: Create CinematicCameraController.kt**

```kotlin
package com.canefe.storyclient.client.decision

import net.minecraft.client.MinecraftClient
import net.minecraft.entity.Entity
import net.minecraft.util.math.Vec3d

object CinematicCameraController {

    private var originalCameraEntity: Entity? = null
    private var active = false
    private var currentPrompt: DecisionPrompt? = null

    // Tracks the last highlight index so we only move the camera when it changes
    private var lastHighlightIndex: Int = Int.MIN_VALUE

    fun start(prompt: DecisionPrompt) {
        val client = MinecraftClient.getInstance()
        originalCameraEntity = client.cameraEntity
        active = true
        currentPrompt = prompt
        lastHighlightIndex = Int.MIN_VALUE
    }

    fun stop() {
        val client = MinecraftClient.getInstance()
        originalCameraEntity?.let { client.setCameraEntity(it) }
        originalCameraEntity = null
        active = false
        currentPrompt = null
    }

    /** Called each client tick (from ClientTickEvents). */
    fun tick() {
        if (!active) return
        val highlightIndex = DecisionState.highlightedVoiceIndex
        if (highlightIndex == lastHighlightIndex) return
        lastHighlightIndex = highlightIndex

        val client = MinecraftClient.getInstance()
        val world = client.world ?: return
        val player = client.player ?: return

        if (highlightIndex == -1) {
            // Top-down phase: position camera directly above the player's location
            // We use a fake camera position by temporarily using a spectator-style offset.
            // Since Minecraft doesn't expose a free camera without a spectator entity,
            // we fall back to restoring the player camera for the top-down phase
            // and rendering a top-down minimap overlay in DecisionHud instead.
            client.setCameraEntity(player)
        } else {
            // NPC face phase: find the NPC entity by name and focus camera on them.
            // We look up the living entity by the character name from npcVoices.
            val voice = currentPrompt?.npcVoices?.getOrNull(highlightIndex) ?: return
            val targetEntity = world.entities.firstOrNull { entity ->
                entity.name.string.equals(voice.name, ignoreCase = true) ||
                entity.customName?.string?.equals(voice.name, ignoreCase = true) == true
            }

            if (targetEntity != null) {
                client.setCameraEntity(targetEntity)
            } else {
                // Entity not loaded/visible — fall back to player camera
                client.setCameraEntity(player)
            }
        }
    }
}
```

- [ ] **Step 2: Compile**

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/client/kotlin/com/canefe/storyclient/client/decision/CinematicCameraController.kt
git commit -m "feat: CinematicCameraController — sequences camera through NPC faces during critical decisions"
```

---

## Task 4: DecisionHud

**Files:**
- Create: `src/client/kotlin/com/canefe/storyclient/client/decision/DecisionHud.kt`

- [ ] **Step 1: Create DecisionHud.kt**

```kotlin
package com.canefe.storyclient.client.decision

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

object DecisionHud {

    // Which option index is hovered (keyboard navigation: 0-based, -1 = none, options.size = freeform)
    private var selectedIndex: Int = -1

    fun render(ctx: DrawContext) {
        if (!DecisionState.isVisible) return

        val client = MinecraftClient.getInstance()
        val sw = client.window.scaledWidth
        val sh = client.window.scaledHeight
        val font = client.textRenderer

        DecisionState.activePrompt?.let { prompt ->
            if (DecisionState.isCritical) {
                // Vignette overlay
                ctx.fill(0, 0, sw, sh, 0xCC000000.toInt())
            }
            renderDecisionPanel(ctx, prompt, sw, sh)
        }

        DecisionState.activeObserve?.let { observe ->
            renderObserveStrip(ctx, observe, sw, sh)
        }
    }

    private fun renderDecisionPanel(ctx: DrawContext, prompt: DecisionPrompt, sw: Int, sh: Int) {
        val client = MinecraftClient.getInstance()
        val font = client.textRenderer

        val panelW = (sw * 0.6f).toInt().coerceAtMost(520)
        val panelX = (sw - panelW) / 2
        var panelY = (sh * 0.15f).toInt()

        // Panel background
        ctx.fill(panelX - 8, panelY - 8, panelX + panelW + 8, sh - 40, 0xDD111111.toInt())
        ctx.fill(panelX - 9, panelY - 9, panelX + panelW + 9, sh - 39, 0xFF444444.toInt())
        ctx.fill(panelX - 8, panelY - 8, panelX + panelW + 8, sh - 40, 0xDD111111.toInt())

        // Countdown timer (top-right)
        val secondsLeft = DecisionState.ticksRemaining / 20
        val timerColor = when {
            secondsLeft > 30 -> 0xFFFFFFFF.toInt()
            secondsLeft > 10 -> 0xFFFFAA00.toInt()
            else -> 0xFFFF4444.toInt()
        }
        val timerText = "%02d:%02d".format(secondsLeft / 60, secondsLeft % 60)
        ctx.drawTextWithShadow(font, timerText, panelX + panelW - font.getWidth(timerText), panelY, timerColor)

        // Title
        ctx.drawTextWithShadow(font, Text.literal(prompt.title).styled { it.withBold(true) }, panelX, panelY, 0xFFFFEEAA.toInt())
        panelY += font.fontHeight + 6

        // Context
        val contextLines = font.wrapLines(Text.literal(prompt.context), panelW)
        contextLines.forEach { line ->
            ctx.drawTextWithShadow(font, line, panelX, panelY, 0xFFCCCCCC.toInt())
            panelY += font.fontHeight + 2
        }
        panelY += 10

        // NPC voices row
        if (prompt.npcVoices.isNotEmpty()) {
            val voiceW = (panelW / prompt.npcVoices.size) - 4
            prompt.npcVoices.forEachIndexed { i, voice ->
                val vx = panelX + i * (voiceW + 4)
                val isHighlighted = i == DecisionState.highlightedVoiceIndex
                val bgColor = if (isHighlighted) 0xFF223344.toInt() else 0xFF1A1A1A.toInt()
                val borderColor = if (isHighlighted) 0xFF4488CC.toInt() else 0xFF333333.toInt()

                ctx.fill(vx, panelY, vx + voiceW, panelY + 52, bgColor)
                // Border
                ctx.fill(vx - 1, panelY - 1, vx + voiceW + 1, panelY + 53, borderColor)
                ctx.fill(vx, panelY, vx + voiceW, panelY + 52, bgColor)

                val stanceColor = when (voice.stance) {
                    "cautious" -> 0xFFAACC44.toInt()
                    "aggressive" -> 0xFFCC4444.toInt()
                    else -> 0xFFAAAAAA.toInt()
                }
                ctx.drawTextWithShadow(font, Text.literal(voice.name).styled { it.withBold(true) }, vx + 4, panelY + 4, stanceColor)
                val opinionLines = font.wrapLines(Text.literal(voice.opinion), voiceW - 8)
                opinionLines.take(2).forEachIndexed { li, line ->
                    ctx.drawTextWithShadow(font, line, vx + 4, panelY + 4 + font.fontHeight + 2 + li * (font.fontHeight + 1), 0xFFBBBBBB.toInt())
                }
            }
            panelY += 62
        }

        // Options
        ctx.drawTextWithShadow(font, "— Choose —", panelX + (panelW - font.getWidth("— Choose —")) / 2, panelY, 0xFF888888.toInt())
        panelY += font.fontHeight + 6

        prompt.options.forEachIndexed { i, option ->
            val isSelected = selectedIndex == i
            val optBg = if (isSelected) 0xFF1E3A5A.toInt() else 0xFF1A1A2A.toInt()
            val optBorder = if (isSelected) 0xFF5599DD.toInt() else 0xFF333355.toInt()
            ctx.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + font.fontHeight + 7, optBorder)
            ctx.fill(panelX, panelY, panelX + panelW, panelY + font.fontHeight + 6, optBg)

            val letter = ('A' + i).toString()
            ctx.drawTextWithShadow(font, "[$letter] ${option.label}", panelX + 6, panelY + 3, 0xFFEEEEEE.toInt())
            if (option.consequenceHint.isNotEmpty()) {
                val hintText = option.consequenceHint
                ctx.drawTextWithShadow(font, hintText, panelX + panelW - font.getWidth(hintText) - 6, panelY + 3, 0xFF888888.toInt())
            }
            panelY += font.fontHeight + 10
        }

        // Freeform option
        if (prompt.allowFreeform) {
            val isFreeformSelected = selectedIndex == prompt.options.size
            val fbg = if (isFreeformSelected || DecisionState.freeformMode) 0xFF1A2A1A.toInt() else 0xFF1A1A1A.toInt()
            val fborder = if (isFreeformSelected || DecisionState.freeformMode) 0xFF44AA44.toInt() else 0xFF333333.toInt()
            ctx.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + font.fontHeight + 7, fborder)
            ctx.fill(panelX, panelY, panelX + panelW, panelY + font.fontHeight + 6, fbg)

            val freeformDisplay = if (DecisionState.freeformMode)
                "> ${DecisionState.freeformInput}█"
            else
                "[✏] Say something else..."
            ctx.drawTextWithShadow(font, freeformDisplay, panelX + 6, panelY + 3, 0xFF88CC88.toInt())
        }
    }

    private fun renderObserveStrip(ctx: DrawContext, observe: DecisionObserve, sw: Int, sh: Int) {
        val client = MinecraftClient.getInstance()
        val font = client.textRenderer
        val stripH = font.fontHeight + 10
        val y = sh - stripH - 10

        ctx.fill(0, y - 2, sw, y + stripH + 2, 0xCC111111.toInt())
        val msg = "${observe.leaderName} is making a decision..."
        ctx.drawTextWithShadow(font, msg, (sw - font.getWidth(msg)) / 2, y + 5, 0xFFCCCC88.toInt())
    }

    /** Call from KeyboardMixin or ClientTickEvents to handle key presses during an active decision. */
    fun handleKeyPress(keyCode: Int): Boolean {
        val prompt = DecisionState.activePrompt ?: return false

        if (DecisionState.freeformMode) {
            return handleFreeformKey(keyCode, prompt)
        }

        val optionCount = prompt.options.size + (if (prompt.allowFreeform) 1 else 0)

        return when (keyCode) {
            // GLFW key codes: 1=49, 2=50 ... 9=57
            in 49..57 -> {
                val idx = keyCode - 49
                if (idx < prompt.options.size) {
                    submitChoice(prompt, prompt.options[idx].id)
                    true
                } else false
            }
            // Arrow up/down for navigation
            265 -> { // GLFW_KEY_UP
                selectedIndex = (selectedIndex - 1 + optionCount).coerceAtLeast(0)
                true
            }
            264 -> { // GLFW_KEY_DOWN
                selectedIndex = (selectedIndex + 1) % optionCount
                true
            }
            // Enter to confirm selection
            257 -> { // GLFW_KEY_ENTER
                if (selectedIndex >= 0 && selectedIndex < prompt.options.size) {
                    submitChoice(prompt, prompt.options[selectedIndex].id)
                    true
                } else if (prompt.allowFreeform && selectedIndex == prompt.options.size) {
                    DecisionState.freeformMode = true
                    true
                } else false
            }
            else -> false
        }
    }

    private fun handleFreeformKey(keyCode: Int, prompt: DecisionPrompt): Boolean {
        return when (keyCode) {
            259 -> { // GLFW_KEY_BACKSPACE
                if (DecisionState.freeformInput.isNotEmpty()) {
                    DecisionState.freeformInput = DecisionState.freeformInput.dropLast(1)
                }
                true
            }
            257 -> { // GLFW_KEY_ENTER
                val text = DecisionState.freeformInput.trim()
                if (text.isNotEmpty()) {
                    DecisionPacketReceiver.sendResponse(prompt.decisionId, null, text)
                    selectedIndex = -1
                }
                true
            }
            256 -> { // GLFW_KEY_ESCAPE
                DecisionState.freeformMode = false
                DecisionState.freeformInput = ""
                true
            }
            else -> false
        }
    }

    private fun submitChoice(prompt: DecisionPrompt, choiceId: String) {
        DecisionPacketReceiver.sendResponse(prompt.decisionId, choiceId, null)
        selectedIndex = -1
    }

    /** Append a character to freeform input (called from CharTyped event). */
    fun appendFreeformChar(char: Char) {
        if (DecisionState.freeformMode && char.isLetterOrDigit() || char == ' ' || char.code > 31) {
            DecisionState.freeformInput += char
        }
    }
}
```

- [ ] **Step 2: Compile**

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`. Fix any type errors (e.g. `font.wrapLines` signature may vary by MC version — use `client.textRenderer.wrapLines(Text, Int)` or equivalent).

- [ ] **Step 3: Commit**

```bash
git add src/client/kotlin/com/canefe/storyclient/client/decision/DecisionHud.kt
git commit -m "feat: DecisionHud — full-screen and ambient decision UI with NPC voices and options"
```

---

## Task 5: Wire everything into NPCMessageParserClient

**Files:**
- Modify: `src/client/kotlin/com/canefe/storyclient/client/NPCMessageParserClient.kt`

- [ ] **Step 1: Find the onInitializeClient body and add the following registrations**

In `onInitializeClient()`, after the existing payload registrations, add:

```kotlin
// Register decision system
DecisionPacketReceiver.register()

// Decision HUD rendering
HudRenderCallback.EVENT.register { ctx, _ ->
    DecisionHud.render(ctx)
}

// Decision tick (countdown + camera cycling)
ClientTickEvents.END_CLIENT_TICK.register {
    DecisionState.tick()
    CinematicCameraController.tick()
}
```

Add imports at the top of the file:
```kotlin
import com.canefe.storyclient.client.decision.CinematicCameraController
import com.canefe.storyclient.client.decision.DecisionHud
import com.canefe.storyclient.client.decision.DecisionPacketReceiver
import com.canefe.storyclient.client.decision.DecisionState
```

- [ ] **Step 2: Wire keyboard input — find any existing key handler or Screen override**

Check if there's a mixin or key event already registered. If there is a `ClientTickEvents` or `KeyboardInputEvent` registration you can add onto, add:

```kotlin
// Decision key handling
net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register { client ->
    // Key press handling happens via the key callback — see step 3
}
```

For key presses, find `PacketEventsPacketListener` or any existing keyboard mixin. If none exists, add a `ScreenEvents.BEFORE_INIT` callback that intercepts keys only when `DecisionState.isVisible`:

```kotlin
net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.BEFORE_INIT.register { client, screen, scaledWidth, scaledHeight ->
    // When a decision is active, block normal key handling from passing through
}
```

The simplest approach: check `DecisionState.isVisible` inside the existing `ClientTickEvents.END_CLIENT_TICK` loop and call `DecisionHud.handleKeyPress()` based on `MinecraftClient.getInstance().options` key states. Keyboard integration may need a mixin — note this as a follow-up if the simple approach doesn't work.

- [ ] **Step 3: Compile**

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/client/kotlin/com/canefe/storyclient/client/NPCMessageParserClient.kt
git commit -m "feat: wire decision system into NPCMessageParserClient — HUD, ticks, packets"
```

---

## Task 6: Build and manual smoke test

- [ ] **Step 1: Build the mod jar**

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew build
```

Expected: `BUILD SUCCESSFUL`, jar in `build/libs/`

- [ ] **Step 2: Launch the Fabric client via the run config**

Use the `Minecraft Client` run configuration in IntelliJ or:

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew runClient
```

- [ ] **Step 3: Test ambient decision**

Using a temporary debug command on the Story.kt side (or direct WebSocket injection), send a `decision.prompt` with `urgency: "ambient"` to yourself. Verify:
- HUD panel slides in from the bottom
- NPC voices visible
- Options visible
- Pressing `1`/`2`/`3` sends a response packet

- [ ] **Step 4: Test critical decision**

Send a `decision.prompt` with `urgency: "critical"`. Verify:
- Full-screen vignette appears
- Camera cycles to each NPC entity (if NPCs are in the world)
- Countdown timer counts down
- Freeform mode activates on `[✏]` selection, accepts typed input, sends on Enter

- [ ] **Step 5: Test observe mode**

Send a `decision.observe` packet. Verify the observer strip appears at the bottom with the leader's name.

- [ ] **Step 6: Commit any fixes**

```bash
git add -p
git commit -m "fix: <describe issues found>"
```
