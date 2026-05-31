/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  imgui.ImDrawData
 *  imgui.ImFontAtlas
 *  imgui.ImGui
 *  imgui.ImGuiIO
 *  imgui.ImGuiViewport
 *  imgui.ImVec4
 *  imgui.callback.ImPlatformFuncViewport
 *  imgui.type.ImInt
 *  org.lwjgl.opengl.GL20
 *  org.lwjgl.opengl.GL32
 *  org.lwjgl.opengl.GL33
 */
package com.canefe.storyclient.client.dm.imgui;

import imgui.ImDrawData;
import imgui.ImFontAtlas;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImGuiViewport;
import imgui.ImVec4;
import imgui.callback.ImPlatformFuncViewport;
import imgui.type.ImInt;
import java.nio.ByteBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;

public class StoryImGuiImplGl3 {
    // DIAGNOSTIC: print GL error after each step on first frame to locate exactly
    // which call is failing. Drop once we know.
    private boolean diagLogged = false;
    private void diag(String tag) {
        if (diagLogged) return;
        int e = GL32.glGetError();
        if (e != 0) System.err.println("[gl3-diag] " + tag + " -> err=" + e);
    }
    private void diagDone() { diagLogged = true; }

    protected static final String OS = System.getProperty("os.name", "generic").toLowerCase();
    protected static final boolean IS_APPLE = OS.contains("mac") || OS.contains("darwin");
    protected Data data = null;
    private final Properties props = new Properties();

    protected Data newData() {
        return new Data();
    }

    public boolean init() {
        return this.init(null);
    }

    public boolean init(String glslVersion) {
        this.data = this.newData();
        ImGuiIO io = com.canefe.storyclient.client.dm.DMPanelManager.getIO();
        io.setBackendRendererName("imgui-java_impl_opengl3");
        int[] major = new int[1];
        int[] minor = new int[1];
        GL32.glGetIntegerv((int)33307, (int[])major);
        GL32.glGetIntegerv((int)33308, (int[])minor);
        this.data.glVersion = major[0] * 100 + minor[0] * 10;
        if (this.data.glVersion >= 320) {
            io.addBackendFlags(8);
        }
        io.addBackendFlags(4096);
        this.data.glslVersion = glslVersion == null ? (IS_APPLE ? "#version 150" : "#version 130") : glslVersion;
        int[] currentTexture = new int[1];
        GL32.glGetIntegerv((int)32873, (int[])currentTexture);
        boolean bl = this.data.hasClipOrigin = this.data.glVersion >= 450;
        if (com.canefe.storyclient.client.dm.DMPanelManager.getIO().hasConfigFlags(1024)) {
            this.initPlatformInterface();
        }
        return true;
    }

    public void shutdown() {
        ImGuiIO io = com.canefe.storyclient.client.dm.DMPanelManager.getIO();
        this.shutdownPlatformInterface();
        this.destroyDeviceObjects();
        io.setBackendRendererName(null);
        this.data = null;
    }

    public void newFrame() {
        if (this.data.shaderHandle == 0) {
            this.createDeviceObjects();
        }
    }

    protected void setupRenderState(ImDrawData drawData, int fbWidth, int fbHeight, int gVertexArrayObject) {
        GL32.glEnable((int)3042);
        GL32.glBlendEquation((int)32774);
        GL32.glBlendFuncSeparate((int)770, (int)771, (int)1, (int)771);
        GL32.glDisable((int)2884);
        GL32.glDisable((int)2929);
        GL32.glDisable((int)2960);
        GL32.glEnable((int)3089);
        if (this.data.glVersion >= 310) {
            GL32.glDisable((int)36765);
        }
        if (this.data.glVersion >= 200) {
            GL32.glPolygonMode((int)1032, (int)6914);
        }
        boolean clipOriginLowerLeft = true;
        if (this.data.hasClipOrigin) {
            int[] currentClipOrigin = new int[1];
            GL32.glGetIntegerv((int)37724, (int[])currentClipOrigin);
            if (currentClipOrigin[0] == 36002) {
                clipOriginLowerLeft = false;
            }
        }
        // FORK NOTE: bind the back-buffer FBO explicitly. Minecraft leaves its
        // main render target FBO bound after blitToScreen; without this, ImGui
        // would draw into MC's framebuffer and we'd never see it.
        GL32.glBindFramebuffer((int)36160 /* GL_FRAMEBUFFER */, 0);
        diag("setup/after-bindFB0");
        GL32.glViewport((int)0, (int)0, (int)fbWidth, (int)fbHeight);
        diag("setup/after-viewport");
        float L = drawData.getDisplayPosX();
        float R = drawData.getDisplayPosX() + drawData.getDisplaySizeX();
        float T = drawData.getDisplayPosY();
        float B = drawData.getDisplayPosY() + drawData.getDisplaySizeY();
        if (this.data.hasClipOrigin && !clipOriginLowerLeft) {
            float tmp = T;
            T = B;
            B = tmp;
        }
        ((Properties)this.props).orthoProjMatrix[0] = 2.0f / (R - L);
        ((Properties)this.props).orthoProjMatrix[5] = 2.0f / (T - B);
        ((Properties)this.props).orthoProjMatrix[10] = -1.0f;
        ((Properties)this.props).orthoProjMatrix[12] = (R + L) / (L - R);
        ((Properties)this.props).orthoProjMatrix[13] = (T + B) / (B - T);
        ((Properties)this.props).orthoProjMatrix[15] = 1.0f;
        GL32.glUseProgram((int)this.data.shaderHandle);
        diag("setup/after-useProgram h="+this.data.shaderHandle);
        GL32.glUniform1i((int)this.data.attribLocationTex, (int)0);
        diag("setup/after-uniform1i");
        GL32.glUniformMatrix4fv((int)this.data.attribLocationProjMtx, (boolean)false, (float[])this.props.orthoProjMatrix);
        diag("setup/after-uniformMtx");
        if (this.data.glVersion >= 330) {
            GL33.glBindSampler((int)0, (int)0);
        }
        GL32.glBindVertexArray((int)gVertexArrayObject);
        diag("setup/after-bindVAO vao="+gVertexArrayObject);
        GL32.glBindBuffer((int)34962, (int)this.data.vboHandle);
        diag("setup/after-bindVBO");
        GL32.glBindBuffer((int)34963, (int)this.data.elementsHandle);
        diag("setup/after-bindEBO");
        GL32.glEnableVertexAttribArray((int)this.data.attribLocationVtxPos);
        diag("setup/after-enableVtxPos loc="+this.data.attribLocationVtxPos);
        GL32.glEnableVertexAttribArray((int)this.data.attribLocationVtxUV);
        diag("setup/after-enableVtxUV loc="+this.data.attribLocationVtxUV);
        GL32.glEnableVertexAttribArray((int)this.data.attribLocationVtxColor);
        diag("setup/after-enableVtxColor loc="+this.data.attribLocationVtxColor);
        GL32.glVertexAttribPointer((int)this.data.attribLocationVtxPos, (int)2, (int)5126, (boolean)false, (int)ImDrawData.sizeOfImDrawVert(), (long)0L);
        GL32.glVertexAttribPointer((int)this.data.attribLocationVtxUV, (int)2, (int)5126, (boolean)false, (int)ImDrawData.sizeOfImDrawVert(), (long)8L);
        GL32.glVertexAttribPointer((int)this.data.attribLocationVtxColor, (int)4, (int)5121, (boolean)true, (int)ImDrawData.sizeOfImDrawVert(), (long)16L);
        diag("setup/after-vtxAttribPointers");
    }

    public void renderDrawData(ImDrawData drawData) {
        int fbWidth = (int)(drawData.getDisplaySizeX() * drawData.getFramebufferScaleX());
        int fbHeight = (int)(drawData.getDisplaySizeY() * drawData.getFramebufferScaleY());
        if (fbWidth <= 0 || fbHeight <= 0) {
            return;
        }
        if (drawData.getCmdListsCount() <= 0) {
            return;
        }
        GL32.glGetIntegerv((int)34016, (int[])this.props.lastActiveTexture);
        GL32.glActiveTexture((int)33984);
        GL32.glGetIntegerv((int)35725, (int[])this.props.lastProgram);
        GL32.glGetIntegerv((int)32873, (int[])this.props.lastTexture);
        if (this.data.glVersion >= 330) {
            GL32.glGetIntegerv((int)35097, (int[])this.props.lastSampler);
        }
        GL32.glGetIntegerv((int)34964, (int[])this.props.lastArrayBuffer);
        GL32.glGetIntegerv((int)34229, (int[])this.props.lastVertexArrayObject);
        if (this.data.glVersion >= 200) {
            GL32.glGetIntegerv((int)2880, (int[])this.props.lastPolygonMode);
        }
        GL32.glGetIntegerv((int)2978, (int[])this.props.lastViewport);
        GL32.glGetIntegerv((int)3088, (int[])this.props.lastScissorBox);
        GL32.glGetIntegerv((int)32969, (int[])this.props.lastBlendSrcRgb);
        GL32.glGetIntegerv((int)32968, (int[])this.props.lastBlendDstRgb);
        GL32.glGetIntegerv((int)32971, (int[])this.props.lastBlendSrcAlpha);
        GL32.glGetIntegerv((int)32970, (int[])this.props.lastBlendDstAlpha);
        GL32.glGetIntegerv((int)32777, (int[])this.props.lastBlendEquationRgb);
        GL32.glGetIntegerv((int)34877, (int[])this.props.lastBlendEquationAlpha);
        this.props.lastEnableBlend = GL32.glIsEnabled((int)3042);
        this.props.lastEnableCullFace = GL32.glIsEnabled((int)2884);
        this.props.lastEnableDepthTest = GL32.glIsEnabled((int)2929);
        this.props.lastEnableStencilTest = GL32.glIsEnabled((int)2960);
        this.props.lastEnableScissorTest = GL32.glIsEnabled((int)3089);
        if (this.data.glVersion >= 310) {
            this.props.lastEnablePrimitiveRestart = GL32.glIsEnabled((int)36765);
        }
        diag("after-state-backup");
        int vertexArrayObject = GL32.glGenVertexArrays();
        diag("after-genVAO");
        this.setupRenderState(drawData, fbWidth, fbHeight, vertexArrayObject);
        diag("after-setupRenderState");
        float clipOffX = drawData.getDisplayPosX();
        float clipOffY = drawData.getDisplayPosY();
        float clipScaleX = drawData.getFramebufferScaleX();
        float clipScaleY = drawData.getFramebufferScaleY();
        for (int n = 0; n < drawData.getCmdListsCount(); ++n) {
            GL32.glBufferData((int)34962, (ByteBuffer)drawData.getCmdListVtxBufferData(n), (int)35040);
            diag("after-vtxBufferData n="+n);
            GL32.glBufferData((int)34963, (ByteBuffer)drawData.getCmdListIdxBufferData(n), (int)35040);
            diag("after-idxBufferData n="+n);
            for (int cmdIdx = 0; cmdIdx < drawData.getCmdListCmdBufferSize(n); ++cmdIdx) {
                drawData.getCmdListCmdBufferClipRect(this.props.clipRect, n, cmdIdx);
                float clipMinX = (((Properties)this.props).clipRect.x - clipOffX) * clipScaleX;
                float clipMinY = (((Properties)this.props).clipRect.y - clipOffY) * clipScaleY;
                float clipMaxX = (((Properties)this.props).clipRect.z - clipOffX) * clipScaleX;
                float clipMaxY = (((Properties)this.props).clipRect.w - clipOffY) * clipScaleY;
                if (clipMaxX <= clipMinX || clipMaxY <= clipMinY) continue;
                GL32.glScissor((int)((int)clipMinX), (int)((int)((float)fbHeight - clipMaxY)), (int)((int)(clipMaxX - clipMinX)), (int)((int)(clipMaxY - clipMinY)));
                long textureId = drawData.getCmdListCmdBufferTextureId(n, cmdIdx);
                int elemCount = drawData.getCmdListCmdBufferElemCount(n, cmdIdx);
                int idxOffset = drawData.getCmdListCmdBufferIdxOffset(n, cmdIdx);
                int vtxOffset = drawData.getCmdListCmdBufferVtxOffset(n, cmdIdx);
                long indices = (long)idxOffset * (long)ImDrawData.sizeOfImDrawIdx();
                int type = ImDrawData.sizeOfImDrawIdx() == 2 ? 5123 : 5125;
                GL32.glBindTexture((int)3553, (int)((int)textureId));
                diag("after-bindTex");
                if (this.data.glVersion >= 320) {
                    GL32.glDrawElementsBaseVertex((int)4, (int)elemCount, (int)type, (long)indices, (int)vtxOffset);
                    diag("after-drawBaseVertex");
                    continue;
                }
                GL32.glDrawElements((int)4, (int)elemCount, (int)type, (long)indices);
                diag("after-drawElements");
            }
        }
        GL32.glDeleteVertexArrays((int)vertexArrayObject);
        diagDone();
        GL32.glUseProgram((int)this.props.lastProgram[0]);
        GL32.glBindTexture((int)3553, (int)this.props.lastTexture[0]);
        if (this.data.glVersion >= 330) {
            GL33.glBindSampler((int)0, (int)this.props.lastSampler[0]);
        }
        GL32.glActiveTexture((int)this.props.lastActiveTexture[0]);
        GL32.glBindVertexArray((int)this.props.lastVertexArrayObject[0]);
        GL32.glBindBuffer((int)34962, (int)this.props.lastArrayBuffer[0]);
        GL32.glBlendEquationSeparate((int)this.props.lastBlendEquationRgb[0], (int)this.props.lastBlendEquationAlpha[0]);
        GL32.glBlendFuncSeparate((int)this.props.lastBlendSrcRgb[0], (int)this.props.lastBlendDstRgb[0], (int)this.props.lastBlendSrcAlpha[0], (int)this.props.lastBlendDstAlpha[0]);
        if (this.props.lastEnableBlend) {
            GL32.glEnable((int)3042);
        } else {
            GL32.glDisable((int)3042);
        }
        if (this.props.lastEnableCullFace) {
            GL32.glEnable((int)2884);
        } else {
            GL32.glDisable((int)2884);
        }
        if (this.props.lastEnableDepthTest) {
            GL32.glEnable((int)2929);
        } else {
            GL32.glDisable((int)2929);
        }
        if (this.props.lastEnableStencilTest) {
            GL32.glEnable((int)2960);
        } else {
            GL32.glDisable((int)2960);
        }
        if (this.props.lastEnableScissorTest) {
            GL32.glEnable((int)3089);
        } else {
            GL32.glDisable((int)3089);
        }
        if (this.data.glVersion >= 310) {
            if (this.props.lastEnablePrimitiveRestart) {
                GL32.glEnable((int)36765);
            } else {
                GL32.glDisable((int)36765);
            }
        }
        if (this.data.glVersion >= 200) {
            GL32.glPolygonMode((int)1032, (int)this.props.lastPolygonMode[0]);
        }
        GL32.glViewport((int)this.props.lastViewport[0], (int)this.props.lastViewport[1], (int)this.props.lastViewport[2], (int)this.props.lastViewport[3]);
        GL32.glScissor((int)this.props.lastScissorBox[0], (int)this.props.lastScissorBox[1], (int)this.props.lastScissorBox[2], (int)this.props.lastScissorBox[3]);
    }

    public boolean createFontsTexture() {
        ImFontAtlas fontAtlas = com.canefe.storyclient.client.dm.DMPanelManager.getIO().getFonts();
        ImInt width = new ImInt();
        ImInt height = new ImInt();
        ByteBuffer pixels = fontAtlas.getTexDataAsRGBA32(width, height);
        int[] lastTexture = new int[1];
        GL32.glGetIntegerv((int)32873, (int[])lastTexture);
        this.data.fontTexture = GL32.glGenTextures();
        GL32.glBindTexture((int)3553, (int)this.data.fontTexture);
        GL32.glTexParameteri((int)3553, (int)10241, (int)9729);
        GL32.glTexParameteri((int)3553, (int)10240, (int)9729);
        GL32.glPixelStorei((int)3317, (int)4);
        GL32.glPixelStorei((int)3316, (int)0);
        GL32.glPixelStorei((int)3315, (int)0);
        GL32.glPixelStorei((int)3314, (int)0);
        GL32.glTexImage2D((int)3553, (int)0, (int)6408, (int)width.get(), (int)height.get(), (int)0, (int)6408, (int)5121, (ByteBuffer)pixels);
        fontAtlas.setTexID((long)this.data.fontTexture);
        GL32.glBindTexture((int)3553, (int)lastTexture[0]);
        return true;
    }

    public void destroyFontsTexture() {
        ImGuiIO io = com.canefe.storyclient.client.dm.DMPanelManager.getIO();
        if (this.data.fontTexture != 0) {
            GL32.glDeleteTextures((int)this.data.fontTexture);
            io.getFonts().setTexID(0L);
            this.data.fontTexture = 0;
        }
    }

    protected boolean checkShader(int handle, String desc) {
        int[] status = new int[1];
        int[] logLength = new int[1];
        GL32.glGetShaderiv((int)handle, (int)35713, (int[])status);
        GL32.glGetShaderiv((int)handle, (int)35716, (int[])logLength);
        if (status[0] == 0) {
            System.err.printf("%s: failed to compile %s! With GLSL: %s\n", this, desc, this.data.glslVersion);
        }
        if (logLength[0] > 1) {
            String log = GL32.glGetShaderInfoLog((int)handle);
            System.err.println(log);
        }
        return status[0] == 1;
    }

    protected boolean checkProgram(int handle, String desc) {
        int[] status = new int[1];
        int[] logLength = new int[1];
        GL20.glGetProgramiv((int)handle, (int)35714, (int[])status);
        GL20.glGetProgramiv((int)handle, (int)35716, (int[])logLength);
        if (status[0] == 0) {
            System.err.printf("%s: failed to link %s! With GLSL: %s\n", this, desc, this.data.glslVersion);
        }
        if (logLength[0] > 1) {
            String log = GL32.glGetProgramInfoLog((int)handle);
            System.err.println(log);
        }
        return status[0] == 1;
    }

    protected int parseGlslVersionString(String glslVersion) {
        Pattern p = Pattern.compile("\\d+");
        Matcher m = p.matcher(glslVersion);
        if (m.find()) {
            return Integer.parseInt(m.group());
        }
        return 130;
    }

    protected boolean createDeviceObjects() {
        String fragmentShader;
        String vertexShader;
        int[] lastTexture = new int[1];
        int[] lastArrayBuffer = new int[1];
        int[] lastVertexArray = new int[1];
        GL32.glGetIntegerv((int)32873, (int[])lastTexture);
        GL32.glGetIntegerv((int)34964, (int[])lastArrayBuffer);
        GL32.glGetIntegerv((int)34229, (int[])lastVertexArray);
        int glslVersionValue = this.parseGlslVersionString(this.data.glslVersion);
        if (glslVersionValue < 130) {
            vertexShader = this.vertexShaderGlsl120();
            fragmentShader = this.fragmentShaderGlsl120();
        } else if (glslVersionValue >= 410) {
            vertexShader = this.vertexShaderGlsl410Core();
            fragmentShader = this.fragmentShaderGlsl410Core();
        } else if (glslVersionValue == 300) {
            vertexShader = this.vertexShaderGlsl300es();
            fragmentShader = this.fragmentShaderGlsl300es();
        } else {
            vertexShader = this.vertexShaderGlsl130();
            fragmentShader = this.fragmentShaderGlsl130();
        }
        int vertHandle = GL32.glCreateShader((int)35633);
        GL32.glShaderSource((int)vertHandle, (CharSequence)vertexShader);
        GL32.glCompileShader((int)vertHandle);
        this.checkShader(vertHandle, "vertex shader");
        int fragHandle = GL32.glCreateShader((int)35632);
        GL32.glShaderSource((int)fragHandle, (CharSequence)fragmentShader);
        GL32.glCompileShader((int)fragHandle);
        this.checkShader(fragHandle, "fragment shader");
        this.data.shaderHandle = GL32.glCreateProgram();
        GL32.glAttachShader((int)this.data.shaderHandle, (int)vertHandle);
        GL32.glAttachShader((int)this.data.shaderHandle, (int)fragHandle);
        GL32.glLinkProgram((int)this.data.shaderHandle);
        this.checkProgram(this.data.shaderHandle, "shader program");
        GL20.glDetachShader((int)this.data.shaderHandle, (int)vertHandle);
        GL20.glDetachShader((int)this.data.shaderHandle, (int)fragHandle);
        GL20.glDeleteShader((int)vertHandle);
        GL20.glDeleteShader((int)fragHandle);
        this.data.attribLocationTex = GL20.glGetUniformLocation((int)this.data.shaderHandle, (CharSequence)"Texture");
        this.data.attribLocationProjMtx = GL20.glGetUniformLocation((int)this.data.shaderHandle, (CharSequence)"ProjMtx");
        this.data.attribLocationVtxPos = GL20.glGetAttribLocation((int)this.data.shaderHandle, (CharSequence)"Position");
        this.data.attribLocationVtxUV = GL20.glGetAttribLocation((int)this.data.shaderHandle, (CharSequence)"UV");
        this.data.attribLocationVtxColor = GL20.glGetAttribLocation((int)this.data.shaderHandle, (CharSequence)"Color");
        this.data.vboHandle = GL32.glGenBuffers();
        this.data.elementsHandle = GL32.glGenBuffers();
        this.createFontsTexture();
        GL32.glBindTexture((int)3553, (int)lastTexture[0]);
        GL32.glBindBuffer((int)34962, (int)lastArrayBuffer[0]);
        GL32.glBindVertexArray((int)lastVertexArray[0]);
        return true;
    }

    public void destroyDeviceObjects() {
        if (this.data.vboHandle != 0) {
            GL32.glDeleteBuffers((int)this.data.vboHandle);
            this.data.vboHandle = 0;
        }
        if (this.data.elementsHandle != 0) {
            GL32.glDeleteBuffers((int)this.data.elementsHandle);
            this.data.elementsHandle = 0;
        }
        if (this.data.shaderHandle != 0) {
            GL32.glDeleteProgram((int)this.data.shaderHandle);
            this.data.shaderHandle = 0;
        }
        this.destroyFontsTexture();
    }

    protected void initPlatformInterface() {
        ImGui.getPlatformIO().setRendererRenderWindow((ImPlatformFuncViewport)new RendererRenderWindowFunction());
    }

    protected void shutdownPlatformInterface() {
        ImGui.destroyPlatformWindows();
    }

    protected String vertexShaderGlsl120() {
        return this.data.glslVersion + "\nuniform mat4 ProjMtx;\nattribute vec2 Position;\nattribute vec2 UV;\nattribute vec4 Color;\nvarying vec2 Frag_UV;\nvarying vec4 Frag_Color;\nvoid main()\n{\n    Frag_UV = UV;\n    Frag_Color = Color;\n    gl_Position = ProjMtx * vec4(Position.xy,0,1);\n}\n";
    }

    protected String vertexShaderGlsl130() {
        return this.data.glslVersion + "\nuniform mat4 ProjMtx;\nin vec2 Position;\nin vec2 UV;\nin vec4 Color;\nout vec2 Frag_UV;\nout vec4 Frag_Color;\nvoid main()\n{\n    Frag_UV = UV;\n    Frag_Color = Color;\n    gl_Position = ProjMtx * vec4(Position.xy,0,1);\n}\n";
    }

    private String vertexShaderGlsl300es() {
        return this.data.glslVersion + "\nprecision highp float;\nlayout (location = 0) in vec2 Position;\nlayout (location = 1) in vec2 UV;\nlayout (location = 2) in vec4 Color;\nuniform mat4 ProjMtx;\nout vec2 Frag_UV;\nout vec4 Frag_Color;\nvoid main()\n{\n    Frag_UV = UV;\n    Frag_Color = Color;\n    gl_Position = ProjMtx * vec4(Position.xy,0,1);\n}\n";
    }

    protected String vertexShaderGlsl410Core() {
        return this.data.glslVersion + "\nlayout (location = 0) in vec2 Position;\nlayout (location = 1) in vec2 UV;\nlayout (location = 2) in vec4 Color;\nuniform mat4 ProjMtx;\nout vec2 Frag_UV;\nout vec4 Frag_Color;\nvoid main()\n{\n    Frag_UV = UV;\n    Frag_Color = Color;\n    gl_Position = ProjMtx * vec4(Position.xy,0,1);\n}\n";
    }

    protected String fragmentShaderGlsl120() {
        return this.data.glslVersion + "\n#ifdef GL_ES\n    precision mediump float;\n#endif\nuniform sampler2D Texture;\nvarying vec2 Frag_UV;\nvarying vec4 Frag_Color;\nvoid main()\n{\n    gl_FragColor = Frag_Color * texture2D(Texture, Frag_UV.st);\n}\n";
    }

    protected String fragmentShaderGlsl130() {
        return this.data.glslVersion + "\nuniform sampler2D Texture;\nin vec2 Frag_UV;\nin vec4 Frag_Color;\nout vec4 Out_Color;\nvoid main()\n{\n    Out_Color = Frag_Color * texture(Texture, Frag_UV.st);\n}\n";
    }

    protected String fragmentShaderGlsl300es() {
        return this.data.glslVersion + "\nprecision mediump float;\nuniform sampler2D Texture;\nin vec2 Frag_UV;\nin vec4 Frag_Color;\nlayout (location = 0) out vec4 Out_Color;\nvoid main()\n{\n    Out_Color = Frag_Color * texture(Texture, Frag_UV.st);\n}\n";
    }

    protected String fragmentShaderGlsl410Core() {
        return this.data.glslVersion + "\nin vec2 Frag_UV;\nin vec4 Frag_Color;\nuniform sampler2D Texture;\nlayout (location = 0) out vec4 Out_Color;\nvoid main()\n{\n    Out_Color = Frag_Color * texture(Texture, Frag_UV.st);\n}\n";
    }

    private final class RendererRenderWindowFunction
    extends ImPlatformFuncViewport {
        private RendererRenderWindowFunction() {
        }

        public void accept(ImGuiViewport vp) {
            if (!vp.hasFlags(256)) {
                GL32.glClearColor((float)0.0f, (float)0.0f, (float)0.0f, (float)0.0f);
                GL32.glClear((int)16384);
            }
            StoryImGuiImplGl3.this.renderDrawData(vp.getDrawData());
        }
    }

    private static final class Properties {
        private final ImVec4 clipRect = new ImVec4();
        private final float[] orthoProjMatrix = new float[16];
        private final int[] lastActiveTexture = new int[1];
        private final int[] lastProgram = new int[1];
        private final int[] lastTexture = new int[1];
        private final int[] lastSampler = new int[1];
        private final int[] lastArrayBuffer = new int[1];
        private final int[] lastVertexArrayObject = new int[1];
        private final int[] lastPolygonMode = new int[2];
        private final int[] lastViewport = new int[4];
        private final int[] lastScissorBox = new int[4];
        private final int[] lastBlendSrcRgb = new int[1];
        private final int[] lastBlendDstRgb = new int[1];
        private final int[] lastBlendSrcAlpha = new int[1];
        private final int[] lastBlendDstAlpha = new int[1];
        private final int[] lastBlendEquationRgb = new int[1];
        private final int[] lastBlendEquationAlpha = new int[1];
        private boolean lastEnableBlend = false;
        private boolean lastEnableCullFace = false;
        private boolean lastEnableDepthTest = false;
        private boolean lastEnableStencilTest = false;
        private boolean lastEnableScissorTest = false;
        private boolean lastEnablePrimitiveRestart = false;

        private Properties() {
        }
    }

    protected static class Data {
        protected int glVersion = 0;
        protected String glslVersion = "";
        protected int fontTexture = 0;
        protected int shaderHandle = 0;
        protected int attribLocationTex = 0;
        protected int attribLocationProjMtx = 0;
        protected int attribLocationVtxPos = 0;
        protected int attribLocationVtxUV = 0;
        protected int attribLocationVtxColor = 0;
        protected int vboHandle = 0;
        protected int elementsHandle = 0;
        protected boolean hasClipOrigin;

        protected Data() {
        }
    }
}

