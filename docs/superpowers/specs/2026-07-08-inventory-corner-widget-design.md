# Inventory Corner Widget — Design

Date: 2026-07-08
Repos touched: Story (StoryMC), StoryClient

## Goal

Fill the freed-up top-right corner of the survival inventory (the region the
2×2 crafting grid + result slot used to occupy, now painted over by
`InventoryCraftingHiderMixin`) with a small, glanceable status panel — starting
with **coin + carry weight** for the Story TTRPG.

The panel must be **content-agnostic**: StoryMC pushes a generic list of rows,
and the client renders them blindly. Coin and carry weight are just the first
two rows; the client never hardcodes "coin" or "weight" as concepts. This keeps
the client a dumb renderer and StoryMC the owner of all meaning, matching the
frontend-agnostic principle (sim/plugin syncs MEANING, frontend owns MEDIUM).

## Decisions (settled)

- **Scope:** StoryMC plugin → StoryClient only. story-sim / story-go / proto are
  NOT touched. Assume StoryMC already has access to the stats it wants to show.
- **Content model:** a list of **rows**, each row =
  `icon (optional) + label + value + optional bar (0..1)`.
- **Genericity:** fully content-agnostic. No per-stat typing, no semantic tags on
  the wire. The client renders whatever rows arrive, in order.
- **Placement:** inside the vanilla `InventoryScreen`, in the painted-over
  crafting corner. Rendered by the existing `InventoryCraftingHiderMixin` render
  hook — NOT a `StoryTabsPanel` tab (it lives in the inventory panel itself).
- **Push timing:** on player join + on change. StoryMC re-sends whenever the row
  set it would produce changes (diff on a signature, like `HediffRelay`).
- **Empty state:** if no rows (or no data yet), the corner renders nothing
  (stays clean painted-over panel). No placeholder text.

## Data model

### Wire payload (JSON over a plugin message channel)

Channel: `story:inv_widget` (follows the existing `story:*` JSON-channel
convention used by `HediffRelay` etc.).

```
InventoryWidgetPayload {
  rows: [
    {
      icon:  String   // optional; item/sprite id, "" = no icon
      label: String   // e.g. "Gold", "Carry"
      value: String   // e.g. "142", "18/30"
      bar:   Float?    // optional 0..1 fill; null/absent = no bar
    },
    ...
  ]
}
```

- `icon` is a string id the client resolves to a texture (item id or a
  `story:` sprite path). Empty string → render label/value only, no icon column.
- `value` is pre-formatted by StoryMC (the client does no number formatting).
- `bar` present → draw a thin meter under/beside the row at that fill.

Backward/forward compatible: unknown future fields ignored; absent `bar`/`icon`
degrade gracefully.

## Part 1 — StoryMC (Story plugin)

- New `InventoryWidgetRelay` (mirrors `HediffRelay`):
  - Builds the row list from whatever stats the plugin already tracks
    (coin, carry weight). The row-construction is the ONLY place stat semantics
    live; adding a stat = adding a row here, no client change.
  - `signature()` over the row list (icon+label+value+bar per row) to diff; only
    send when it changes. Send once on join, then on change.
  - Serializes `InventoryWidgetPayload` to JSON, sends on `story:inv_widget` to
    the owning player.
- Hook the send:
  - **On join:** wherever player-scoped state is first pushed on join (same spot
    other per-player relays initialize).
  - **On change:** call the relay when a contributing stat mutates. If there is
    no single chokepoint, a lightweight per-player tick that recomputes the
    signature and sends on change is acceptable (still change-gated, not
    unconditional spam) — decide during build by what StoryMC already exposes.

Serialization must go through the plugin's existing outbound JSON path; register
the channel if the plugin gates outbound channels.

## Part 2 — StoryClient

### Plumbing (follows the Skills/Hediff S2C pattern)

- `inventory/InventoryWidgetPacketReceiver.kt`:
  - `InventoryWidgetDTO` (rows: List<RowDTO>, RowDTO = icon/label/value/bar).
  - Registers the `story:inv_widget` receiver in `NPCMessageParserClient`
    alongside the other `ClientPlayNetworking.registerGlobalReceiver` blocks;
    parses JSON, writes `InventoryWidgetState`.
- `inventory/InventoryWidgetState.kt` (object):
  - Holds `rows: List<WidgetRow>` (the current widget content). Updated by the
    receiver, read by the renderer each frame. Pure state, no packets.

### Rendering (in `InventoryCraftingHiderMixin`)

The mixin already computes the painted-over rect
(`STORYCLIENT_CRAFT_X/Y/W/H`, panel-local, positioned via
`HandledScreenLayoutAccessor`). Extend it:

- After the paint-over fill (so it draws ON TOP of the clean panel), render
  `InventoryWidgetState.rows` as a compact vertical list within the rect:
  - Per row: optional icon (16px, via `DrawContext.drawItem`/`drawTexture`
    resolving `icon`), then `label`, then right-aligned `value`, using the
    screen's `textRenderer`.
  - If `bar != null`: a thin 2–3px meter under the row (fill = `bar`,
    background + foreground `fill` rects), colored to match the panel accent.
  - Rows stack top-down; clamp to the rect height (the region fits ~2–3 rows —
    coin + weight = 2, comfortable).
- Keep the render logic in a small private helper on the mixin (or a
  `InventoryWidgetView` object the mixin calls) so the mixin stays thin.
- No new interactivity (display-only); no `mouseClicked` changes.

### Rect budget

Painted-over region is ~x=95, y=15, w≈76, h=40 (panel-local, per the current
constants). That's ~2 rows at `fontHeight + a bar`. If more rows arrive than
fit, render top N and stop (the plugin is expected to send a corner-appropriate
few). Log nothing — silent clamp is fine for a cosmetic corner.

## Build order

1. StoryClient: `InventoryWidgetDTO` + receiver + `InventoryWidgetState`
   (register channel; verify it parses a hand-sent test payload).
2. StoryClient: render rows in `InventoryCraftingHiderMixin` (icon + label +
   value + optional bar), compile.
3. StoryMC: `InventoryWidgetRelay` (coin + carry-weight rows), join + on-change
   send, compile.
4. End-to-end: join, open inventory, confirm coin + weight render in the corner
   and update on change.

## Don't break

- `InventoryCraftingHiderMixin`'s existing behavior: crafting-slot hiding,
  recipe-book force-close, and the paint-over. The widget render is additive,
  drawn after the paint-over.
- The Skills/Health tab system (`StoryTabsPanel`) — unrelated; this widget is
  inventory-panel-internal, not a tab.

## Verify

- StoryClient compile:
  `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew -p /Users/canefe/Projects/personal/StoryClient compileClientJava compileClientKotlin -q`
- StoryMC compile:
  `export JAVA_HOME="$HOME/.sdkman/candidates/java/current" && ./gradlew compileKotlin` (in Story).
- Manual: join a server, open inventory (E), confirm coin + carry weight render
  in the top-right corner and refresh when a stat changes.
