package com.canefe.storyclient.client.wheel

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ChatScreen
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Radial action wheel that opens while a keybind is held and the player is
 * looking at a known NPC.
 *
 * Each segment carries a [Selection] handler. A handler can:
 *   - close the wheel and run an action (terminal), or
 *   - swap the segments in place (transition — e.g. Follow Char → pick target).
 *
 * Releases the keybind to commit the highlighted segment; if no segment is
 * highlighted (mouse near center) the wheel closes without doing anything.
 */
object ActionWheelHud {
    /** Outcome of selecting a segment. */
    private sealed class Selection {
        /** Close the wheel and run [action]. */
        data class Run(val action: () -> Unit) : Selection()

        /** Replace current segments without closing the wheel. */
        data class Transition(val newSegments: List<Segment>) : Selection()

        /** Close with no action. */
        data object CloseSilent : Selection()
    }

    private data class Segment(
        val label: String,
        val onSelect: () -> Selection,
    )

    private fun runChat(command: String) {
        MinecraftClient.getInstance().player?.networkHandler?.sendChatCommand(command)
    }

    private fun quoteIfSpaced(s: String): String =
        if (s.contains(' ')) "\"${s.replace("\"", "\\\"")}\"" else s

    /**
     * Build the server-facing identifier for a wheel entry. Prefer the bare
     * characterId UUID (server detects by UUID shape) so commands work
     * regardless of whether the local client recognizes the real name. Fall
     * back to the quoted name for entries lacking a characterId.
     *
     * Why a bare UUID and not `id:<uuid>`: CommandAPI's TextArgument rejects
     * colons, so any prefix-based scheme dies at parse time.
     */
    private fun npcArg(entry: NearbyNPCCache.Entry): String =
        if (entry.characterId.isNotEmpty()) entry.characterId else quoteIfSpaced(entry.dmLabel)

    private fun primarySegments(entry: NearbyNPCCache.Entry): List<Segment> =
        buildList {
            add(
                Segment("Inspect") { Selection.CloseSilent },
            )
            // DM-only perception-log inspector. Gated on isDmView (set when the
            // server includes a realName, which only DMs get) and on having a
            // characterId to query story-go with.
            if (entry.isDmView && entry.characterId.isNotEmpty()) {
                add(
                    Segment("Perception Log") {
                        Selection.Run {
                            MinecraftClient.getInstance().setScreen(
                                PerceptionLogScreen(entry.characterId, entry.dmLabel),
                            )
                        }
                    },
                )
            }
            if (entry.canSpeakAs) {
                add(
                    Segment("Speak As") {
                        Selection.Run {
                            runChat("setcurnpc ${npcArg(entry)}")
                            MinecraftClient.getInstance().setScreen(ChatScreen("/g "))
                        }
                    },
                )
                add(
                    Segment(if (entry.isFollowing) "Unfollow" else "Follow") {
                        Selection.Run {
                            runChat("story npc follow ${npcArg(entry)}")
                        }
                    },
                )
                add(
                    Segment("Follow Char") {
                        val targets = followCharTargets(entry)
                        if (targets.isEmpty()) Selection.CloseSilent else Selection.Transition(targets)
                    },
                )
                add(
                    Segment("Recognize") {
                        val targets = recognizeCharTargets(entry)
                        if (targets.isEmpty()) Selection.CloseSilent else Selection.Transition(targets)
                    },
                )
                // Puppet group is keyed by characterId so it stays correct even
                // when the DM has "reveal real names" toggled off and only sees
                // descriptors for the targeted NPC.
                val puppetKey = entry.characterId
                val inGroup = puppetKey.isNotEmpty() &&
                    com.canefe.storyclient.client.puppet.PuppetState.groupCharacterIds.contains(puppetKey)
                if (puppetKey.isNotEmpty()) {
                    add(
                        Segment(if (inGroup) "Unpuppet" else "Puppet") {
                            Selection.Run {
                                com.canefe.storyclient.client.puppet.PuppetCommandPayload.toggle(puppetKey)
                                com.canefe.storyclient.client.puppet.PuppetState.localToggle(puppetKey)
                            }
                        },
                    )
                    val isGrabbed =
                        com.canefe.storyclient.client.puppet.PuppetState.grabbedCharacterIds.contains(puppetKey)
                    add(
                        Segment(if (isGrabbed) "Release" else "Grab") {
                            Selection.Run {
                                val nowGrabbed =
                                    com.canefe.storyclient.client.puppet.PuppetState.localGrabToggle(puppetKey)
                                com.canefe.storyclient.client.puppet.PuppetCommandPayload
                                    .dmControl(puppetKey, nowGrabbed)
                            }
                        },
                    )
                }
            }
        }

    /**
     * Open the puppet "right-click on NPC" wheel — three actions for directing
     * the current group at that target NPC.
     *
     * The server's puppet group keys on characterId, so add/remove/toggle
     * commands send [Entry.characterId] regardless of what label the local
     * DM currently sees. Move-to looks up by UUID directly — no name compare.
     */
    fun openPuppetTargetWheel(target: NearbyNPCCache.Entry) {
        if (!com.canefe.storyclient.client.puppet.PuppetState.inPuppetMode) return
        val groupKey = target.characterId
        val targets =
            listOfNotNull(
                Segment("Move group here") {
                    Selection.Run {
                        val client = MinecraftClient.getInstance()
                        val world = client.world ?: return@Run
                        val worldName = world.registryKey.value.path
                        val pos =
                            world.getOtherEntities(
                                null,
                                net.minecraft.util.math.Box.of(client.player!!.pos, 64.0, 64.0, 64.0),
                            ) { it.uuid == target.uuid }.firstOrNull()?.pos
                        pos?.let {
                            com.canefe.storyclient.client.puppet.PuppetCommandPayload
                                .moveTo(worldName, it.x, it.y, it.z)
                        }
                    }
                },
                if (groupKey.isEmpty()) null else Segment(
                    if (com.canefe.storyclient.client.puppet.PuppetState.groupCharacterIds.contains(groupKey))
                        "Remove from group"
                    else "Add to group",
                ) {
                    Selection.Run {
                        com.canefe.storyclient.client.puppet.PuppetCommandPayload.toggle(groupKey)
                        com.canefe.storyclient.client.puppet.PuppetState.localToggle(groupKey)
                    }
                },
                Segment("Cancel") { Selection.CloseSilent },
            )
        sourceNpc = target
        segments = targets
        mouseDx = 0.0
        mouseDy = 0.0
        modal = true
        open = true
    }

    /** Display label + server-facing arg for a follow/recognize target. */
    private data class FollowTarget(val label: String, val arg: String)

    private fun recognizeCharTargets(source: NearbyNPCCache.Entry): List<Segment> {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return emptyList()
        val world = client.world ?: return emptyList()

        val npcTargets =
            NearbyNPCCache.all()
                .filter { it.uuid != source.uuid && it.characterId.isNotEmpty() }
                .map { entry -> FollowTarget(label = entry.dmLabel, arg = entry.characterId) }
        val playerTargets =
            world.players
                .filter { it.uuid != player.uuid }
                .mapNotNull { p ->
                    val charId = NearbyNPCCache.get(p.uuid)?.characterId?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val label = NearbyNPCCache.get(p.uuid)?.dmLabel ?: p.name.string
                    FollowTarget(label = label, arg = charId)
                }

        val targets = (npcTargets + playerTargets).distinctBy { it.arg }
        if (targets.isEmpty()) return emptyList()

        return paginatedRecognizeTargets(targets, page = 0, source)
    }

    private fun paginatedRecognizeTargets(
        targets: List<FollowTarget>,
        page: Int,
        source: NearbyNPCCache.Entry,
    ): List<Segment> {
        val sourceArg = source.characterId
        if (targets.size <= MAX_SEGMENTS) {
            return targets.map { t ->
                Segment(t.label) {
                    Selection.Run { runChat("story recognize $sourceArg ${t.arg}") }
                }
            }
        }

        val pageCount = (targets.size + PAGE_SIZE - 1) / PAGE_SIZE
        val safePage = ((page % pageCount) + pageCount) % pageCount
        val start = safePage * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, targets.size)

        return buildList {
            targets.subList(start, end).forEach { t ->
                add(Segment(t.label) {
                    Selection.Run { runChat("story recognize $sourceArg ${t.arg}") }
                })
            }
            add(Segment("« Prev (${safePage + 1}/$pageCount)") {
                Selection.Transition(paginatedRecognizeTargets(targets, safePage - 1, source))
            })
            add(Segment("Next » (${safePage + 1}/$pageCount)") {
                Selection.Transition(paginatedRecognizeTargets(targets, safePage + 1, source))
            })
        }
    }

    private fun followCharTargets(source: NearbyNPCCache.Entry): List<Segment> {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return emptyList()
        val world = client.world ?: return emptyList()

        val npcTargets =
            NearbyNPCCache.all()
                .filter { it.uuid != source.uuid }
                .map { entry ->
                    val arg = if (entry.characterId.isNotEmpty()) entry.characterId else quoteIfSpaced(entry.dmLabel)
                    FollowTarget(label = entry.dmLabel, arg = arg)
                }
        val playerTargets =
            world.players
                .filter { it.uuid != player.uuid }
                .map { p -> FollowTarget(label = p.name.string, arg = quoteIfSpaced(p.name.string)) }

        // Distinct by label so we don't show two entries for the same player
        val targets = (npcTargets + playerTargets).distinctBy { it.label }
        if (targets.isEmpty()) return emptyList()

        return paginatedFollowTargets(targets, page = 0, source)
    }

    private fun paginatedFollowTargets(
        targets: List<FollowTarget>,
        page: Int,
        source: NearbyNPCCache.Entry,
    ): List<Segment> {
        val sourceArg = if (source.characterId.isNotEmpty()) source.characterId else quoteIfSpaced(source.dmLabel)
        if (targets.size <= MAX_SEGMENTS) {
            return targets.map { t ->
                Segment(t.label) {
                    Selection.Run {
                        runChat("story npc followchar $sourceArg ${t.arg}")
                    }
                }
            }
        }

        val pageCount = (targets.size + PAGE_SIZE - 1) / PAGE_SIZE
        val safePage = ((page % pageCount) + pageCount) % pageCount
        val start = safePage * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, targets.size)
        val pageTargets = targets.subList(start, end)

        return buildList {
            pageTargets.forEach { t ->
                add(
                    Segment(t.label) {
                        Selection.Run { runChat("story npc followchar $sourceArg ${t.arg}") }
                    },
                )
            }
            add(
                Segment("« Prev (${safePage + 1}/$pageCount)") {
                    Selection.Transition(paginatedFollowTargets(targets, safePage - 1, source))
                },
            )
            add(
                Segment("Next » (${safePage + 1}/$pageCount)") {
                    Selection.Transition(paginatedFollowTargets(targets, safePage + 1, source))
                },
            )
        }
    }

    @Volatile var open: Boolean = false
        private set

    /**
     * Stage 1 (primary) is a hold-to-show wheel: R press opens, R release commits.
     * Stage 2+ (transition) is modal: keybind is irrelevant, left-click commits,
     * right-click / Esc cancels. [modal] flag distinguishes the two.
     */
    @Volatile var modal: Boolean = false
        private set

    @Volatile private var sourceNpc: NearbyNPCCache.Entry? = null

    @Volatile private var segments: List<Segment> = emptyList()

    private var mouseDx: Double = 0.0
    private var mouseDy: Double = 0.0

    fun tryOpen(): Boolean {
        // Modal stage already up — R does nothing, user must click to commit/cancel.
        if (open && modal) return true
        if (open) return true

        val entry = NearbyNPCCache.lookedAtAny() ?: return false
        val initial = primarySegments(entry)
        if (initial.isEmpty()) return false
        sourceNpc = entry
        segments = initial
        mouseDx = 0.0
        mouseDy = 0.0
        modal = false
        open = true
        return true
    }

    /**
     * Stage 1 commit (R release). No-op if currently in a modal stage.
     */
    fun closeAndCommit() {
        if (!open || modal) return
        commitHighlighted()
    }

    /** Modal-stage left-click: commit highlighted segment. */
    fun modalConfirm(): Boolean {
        if (!open || !modal) return false
        commitHighlighted()
        return true
    }

    /** Modal-stage right-click / Esc: cancel without action. */
    fun modalCancel(): Boolean {
        if (!open || !modal) return false
        close()
        return true
    }

    private fun commitHighlighted() {
        val highlighted = highlightedIndex()
        if (highlighted == null) {
            close()
            return
        }
        when (val outcome = segments[highlighted].onSelect()) {
            is Selection.Run -> {
                close()
                outcome.action()
            }
            is Selection.Transition -> {
                segments = outcome.newSegments
                mouseDx = 0.0
                mouseDy = 0.0
                // Promote to modal: keybind no longer drives commit; clicks do.
                modal = true
            }
            Selection.CloseSilent -> close()
        }
    }

    fun onMouseDelta(dx: Double, dy: Double) {
        if (!open) return
        mouseDx += dx
        mouseDy += dy
    }

    private fun close() {
        open = false
        modal = false
        sourceNpc = null
        segments = emptyList()
    }

    private fun highlightedIndex(): Int? {
        val n = segments.size
        if (n == 0) return null
        val r = sqrt(mouseDx * mouseDx + mouseDy * mouseDy)
        if (r < DEAD_ZONE) return null
        var theta = atan2(mouseDx, -mouseDy)
        if (theta < 0) theta += 2 * PI
        val sliceSize = 2 * PI / n
        return (theta / sliceSize).toInt().coerceIn(0, n - 1)
    }

    fun render(ctx: DrawContext) {
        if (!open) return
        val n = segments.size
        if (n == 0) return

        val client = MinecraftClient.getInstance()
        val w = client.window.scaledWidth
        val h = client.window.scaledHeight
        val cx = w / 2
        val cy = h / 2

        val highlight = highlightedIndex()
        val sliceSize = 2 * PI / n

        segments.forEachIndexed { i, seg ->
            val midAngle = sliceSize * i + sliceSize / 2
            val px = cx + (RADIUS * sin(midAngle)).toInt()
            val py = cy - (RADIUS * cos(midAngle)).toInt()
            drawButton(ctx, px, py, seg.label, i == highlight)
        }

        sourceNpc?.let { source ->
            val font = client.textRenderer
            val title = source.dmLabel
            val nameWidth = font.getWidth(title)
            ctx.drawTextWithShadow(font, title, cx - nameWidth / 2, cy - RADIUS - 30, 0xFFFFFF.toInt())
        }
    }

    private fun drawButton(
        ctx: DrawContext,
        cx: Int,
        cy: Int,
        label: String,
        highlight: Boolean,
    ) {
        val font = MinecraftClient.getInstance().textRenderer
        val pad = 6
        val w = font.getWidth(label) + pad * 2
        val h = font.fontHeight + pad * 2
        val x1 = cx - w / 2
        val y1 = cy - h / 2
        val x2 = cx + w / 2
        val y2 = cy + h / 2
        val bg = if (highlight) 0xFF447744.toInt() else 0xCC222222.toInt()
        val border = if (highlight) 0xFFAAFFAA.toInt() else 0xFF888888.toInt()
        ctx.fill(x1, y1, x2, y2, bg)
        ctx.fill(x1, y1, x2, y1 + 1, border)
        ctx.fill(x1, y2 - 1, x2, y2, border)
        ctx.fill(x1, y1, x1 + 1, y2, border)
        ctx.fill(x2 - 1, y1, x2, y2, border)
        ctx.drawTextWithShadow(font, label, cx - font.getWidth(label) / 2, cy - font.fontHeight / 2, 0xFFFFFFFF.toInt())
    }

    private const val RADIUS = 80
    private const val DEAD_ZONE = 12.0
    private const val MAX_SEGMENTS = 8
    private const val PAGE_SIZE = MAX_SEGMENTS - 2
}
