# StoryClient (Fabric/Kotlin)

## Architecture

Fabric client mod for rendering NPC dialogues and positional audio.

### Key Classes

- `BubbleRenderer` — World-space speech bubbles above NPCs. Separate dialogue/action queues per NPC (NPCBubbleState with DialogueEntry + ActionEntry). Action-only messages render as scattered word groups around NPC body with stagger animation
- `NPCDialogueHud` — Screen-space HUD dialogue (alternative to bubbles, toggled by config)
- `TypingManager` — Parses `<npc_typing>` messages from server, routes to BubbleRenderer or HUD. Voice sync: voicePending flag holds dialogue until audio arrives (3s timeout)
- `PositionalAudioController` — 3D positional audio with per-NPC volume/panning. Timer thread at 50ms, entity ID caching
- `ClientConnectionMixin` — Catches ConcurrentModificationException to prevent disconnects

### Key Patterns

- All `getOtherEntities()` calls wrapped in try-catch for ConcurrentModificationException
- TypingManager maps use ConcurrentHashMap, iterations use `.toMap()`/`.toList()` snapshots
- Action text detection: `hasPendingAction()` counts asterisks (odd = unclosed action during streaming)
- `drawOutlinedText()` renders darker shade shadow + main text (both SEE_THROUGH to avoid z-fighting)
- Bubble background at z=0.5, border at z=0.3, text at z=-0.1 offset for depth separation

### Config

`StoryClientConfig` (storyclient.json): `useBubbleRenderer`, `messageVanishTime`, `dialogueScale`
