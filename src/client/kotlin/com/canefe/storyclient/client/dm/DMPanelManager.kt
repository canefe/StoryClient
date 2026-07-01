package com.canefe.storyclient.client.dm

import com.canefe.storyclient.client.dm.panels.ActionSenderPanel
import com.canefe.storyclient.client.dm.panels.ActivePlanPanel
import com.canefe.storyclient.client.dm.panels.CharacterListPanel
import com.canefe.storyclient.client.dm.panels.DmHealthPanel
import com.canefe.storyclient.client.dm.panels.InspectorPanel
import com.canefe.storyclient.client.health.HealthPanel
import com.canefe.storyclient.client.skills.SkillsPanel
import imgui.ImFont
import imgui.ImFontConfig
import imgui.ImGui
import imgui.ImGuiIO
import imgui.flag.ImGuiConfigFlags
import imgui.flag.ImGuiDockNodeFlags
import imgui.gl3.ImGuiImplGl3
import imgui.glfw.ImGuiImplGlfw
import imgui.internal.ImGuiContext
import imgui.internal.flag.ImGuiDockNodeFlags as ImGuiDockNodeFlagsInternal
import imgui.internal.ImGui as ImGuiInternal
import net.minecraft.client.MinecraftClient

/**
 * Owns the ImGui context, GLFW + GL3 backends, dock space, and per-frame render
 * dispatch for every [DMPanel]. Modelled on Axiom's `EditorUI`.
 *
 * Hard rule: nothing in here is allowed to outlive the Minecraft window. Init is
 * lazy on first frame (so we have a valid window handle) and tear-down lives in
 * [shutdown], called from the client stopping event.
 */
object DMPanelManager {
    private val imguiGlfw = ImGuiImplGlfw()
    private val imguiGl3 = ImGuiImplGl3()
    private var initialized = false
    @Volatile var active = false
        private set

    /**
     * Long-lived IO wrapper. Set in [init] from `ImGui.getIO().ptr` while our
     * context is bound; safe to use from anywhere because it holds the IO
     * struct pointer directly. The forked GLFW backend reads/writes it without
     * triggering the GImGui-null assert.
     */
    private var longLivedIO: ImGuiIO? = null

    @JvmStatic
    fun getIO(): ImGuiIO {
        return longLivedIO ?: ImGui.getIO()
    }

    private var imGuiContext: ImGuiContext? = null

    /** Inter Medium — set after init(). Pushed/popped by panels that want it. */
    var interFont: ImFont? = null
        private set

    private val panels: List<DMPanel> = listOf(
        CharacterListPanel,
        InspectorPanel,
        DmHealthPanel,
        ActivePlanPanel,
        ActionSenderPanel,
    )

    /** Toggle the overlay. While inactive, no ImGui calls run and the world keeps full input. */
    fun toggle() {
        active = !active
    }

    fun setActive(value: Boolean) {
        active = value
    }

    /**
     * Per-frame entry point. Called from [com.canefe.storyclient.client.mixin.MinecraftClientImGuiMixin]
     * after `RenderTarget.blitToScreen(II)V` returns inside `Minecraft.runTick` — the
     * same hook point Axiom uses. This is the only place where MC has finished
     * its frame, no MC shader/VAO/scissor is bound, and the framebuffer is
     * presented, so ImGui can draw cleanly over the back buffer.
     *
     * Wiring it to `HudRenderCallback` instead leaves MC's HUD shader bound and
     * the panel renders silently (no errors, no pixels).
     */
    fun render() {
        if (!active && !HealthPanel.isOpen() && !SkillsPanel.isOpen()) return
        if (!initialized) init()

        // Frame order taken from FlorianMichael/fabric-imgui-example-mod (1.21.1):
        //   imguiGl3.newFrame()   — finalizes GL device objects for the frame
        //   imguiGlfw.newFrame()  — updates input state
        //   ImGui.newFrame()      — opens the ImGui frame
        // Missing the GL3 newFrame is what made glUseProgram succeed but every
        // subsequent uniform/buffer call fail with GL_INVALID_OPERATION.
        imguiGl3.newFrame()
        imguiGlfw.newFrame()
        ImGui.newFrame()

        val dockId = ImGui.dockSpaceOverViewport(
            ImGui.getMainViewport(),
            ImGuiDockNodeFlags.PassthruCentralNode,
        )
        ImGuiInternal.dockBuilderGetCentralNode(dockId)
            .addLocalFlags(ImGuiDockNodeFlagsInternal.NoTabBar)

        if (active) {
            renderMenuBar()
            panels.forEach { runCatching { it.render() } }
        }
        runCatching { HealthPanel.render() }
        runCatching { SkillsPanel.render() }

        ImGui.render()
        imguiGl3.renderDrawData(ImGui.getDrawData())
    }

    /** Should ImGui swallow this input frame? Mirrors Axiom's keybind gating. */
    fun wantsMouse(): Boolean =
        (active || HealthPanel.isOpen() || SkillsPanel.isOpen()) && initialized && getIO().wantCaptureMouse
    fun wantsKeyboard(): Boolean =
        (active || HealthPanel.isOpen() || SkillsPanel.isOpen()) && initialized && getIO().wantCaptureKeyboard

    fun shutdown() {
        if (!initialized) return
        imguiGl3.shutdown()
        imguiGlfw.shutdown()
        ImGui.destroyContext()
        initialized = false
    }

    private fun init() {
        // Axiom (1.86.11) restored the old context after init because the ImGuiIO
        // C-side methods didn't check &g.IO == this — they wrote straight to the
        // IO struct via raw pointer. From imgui-java 1.87 onward the native side
        // asserts that the IO struct belongs to the CURRENTLY BOUND context. That
        // means the GLFW key/mouse callbacks (which fire from glfwPollEvents,
        // outside our render() push/pop window) must find our context bound.
        //
        // Fix: don't restore. Make our context permanently current. No other mod
        // in this client uses ImGui, so there's nothing to preserve.
        imGuiContext = ImGuiContext(ImGui.createContext().ptr)
        ImGui.setCurrentContext(imGuiContext)

        val io: ImGuiIO = ImGuiIO(ImGui.getIO().ptr)
        longLivedIO = io
        io.iniFilename = "config/storyclient-imgui.ini"
        io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard)
        io.addConfigFlags(ImGuiConfigFlags.DockingEnable)

        loadFonts(io)

        val window = MinecraftClient.getInstance().window.handle
        imguiGlfw.init(window, true)
        imguiGl3.init("#version 150")
        initialized = true
    }

    private fun loadFonts(io: ImGuiIO) {
        val ttfBytes = runCatching {
            DMPanelManager::class.java
                .getResourceAsStream("/assets/storyclient/fonts/Inter-Medium.ttf")
                ?.use { it.readBytes() }
        }.getOrNull()

        if (ttfBytes == null) {
            io.fonts.addFontDefault()
        } else {
            val cfg = ImFontConfig().apply {
                oversampleH = 2
                oversampleV = 2
                pixelSnapH = true
                setName("Inter Medium, 16px")
            }
            interFont = io.fonts.addFontFromMemoryTTF(ttfBytes, 16f, cfg)
            cfg.destroy()
        }
        io.fonts.build()
    }

    private fun renderMenuBar() {
        if (!ImGui.beginMainMenuBar()) return
        if (ImGui.beginMenu("View")) {
            DMPanelType.entries.forEach { type ->
                val open = type.isOpen()
                if (ImGui.menuItem(type.displayName, "", open)) {
                    type.setOpen(!open)
                }
            }
            ImGui.separator()
            if (ImGui.menuItem("Reset Layout")) {
                DMPanelType.resetToDefaults()
            }
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("DM")) {
            if (ImGui.menuItem("Close DM Mode", "")) {
                active = false
            }
            ImGui.endMenu()
        }
        ImGui.endMainMenuBar()
    }
}
