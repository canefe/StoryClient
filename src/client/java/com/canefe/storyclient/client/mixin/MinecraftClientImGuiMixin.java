package com.canefe.storyclient.client.mixin;

import com.canefe.storyclient.client.dm.DMPanelManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks {@code MinecraftClient.render} just after {@code Framebuffer.draw(II)V}
 * to draw the DM Control Panel (ImGui) overlay on a clean GL state, mirroring
 * Axiom's {@code MixinMinecraft.afterMainBlit}.
 *
 * <p>Why not {@code HudRenderCallback}? That fires while MC's HUD shader,
 * scissor, and depth state are bound; ImGui's GL3 backend silently renders
 * into a corrupted pass and nothing visible appears. After {@code blitToScreen}
 * the framebuffer has been presented and the GL state is benign — ImGui draws
 * straight to the back buffer.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientImGuiMixin {

    private static boolean firstInject = true;

    @Inject(
        method = "render(Z)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gl/Framebuffer;draw(II)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void storyclient$drawDmPanel(boolean tick, CallbackInfo ci) {
        if (firstInject) {
            firstInject = false;
            System.err.println("[storyclient/dm] mixin fired — after Framebuffer.draw(II)V");
        }
        if (!RenderSystem.isOnRenderThread()) return;
        DMPanelManager.INSTANCE.render();
    }
}
