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

    var ticksRemaining: Int = 0
        private set

    var highlightedVoiceIndex: Int = 0
        private set

    val votes: MutableMap<String, String> = mutableMapOf()

    private var _freeformMode: Boolean = false
    var freeformMode: Boolean
        get() = _freeformMode
        set(value) {
            if (_freeformMode == value) return
            _freeformMode = value
            val mc = net.minecraft.client.MinecraftClient.getInstance()
            // Always toggle the screen on the client thread.
            mc.execute {
                if (value) {
                    if (mc.currentScreen !is DecisionFreeformScreen) {
                        mc.setScreen(DecisionFreeformScreen())
                    }
                } else {
                    if (mc.currentScreen is DecisionFreeformScreen) {
                        mc.setScreen(null)
                    }
                }
            }
        }
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
        // The leader receives both prompt and observe (server broadcasts observe to all online players).
        // If we already have a prompt for this decision, ignore the observe so the full panel stays.
        if (activePrompt?.decisionId == observe.decisionId) return
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
