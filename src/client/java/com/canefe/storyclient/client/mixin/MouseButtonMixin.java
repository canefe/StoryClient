package com.canefe.storyclient.client.mixin;

import com.canefe.storyclient.client.wheel.ActionWheelHud;
import net.minecraft.client.Mouse;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When the action wheel is in its modal stage, intercept mouse clicks:
 * left = commit highlighted segment, right = cancel. The click is swallowed
 * so the player doesn't also break a block / swing / etc.
 */
@Mixin(Mouse.class)
public abstract class MouseButtonMixin {
    @Inject(method = "onMouseButton(JIII)V", at = @At("HEAD"), cancellable = true)
    private void storyclient$wheelInterceptClick(
        long window, int button, int action, int mods, CallbackInfo ci
    ) {
        if (action != GLFW.GLFW_PRESS) return;
        if (!ActionWheelHud.INSTANCE.getModal()) return;

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            ActionWheelHud.INSTANCE.modalConfirm();
            ci.cancel();
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            ActionWheelHud.INSTANCE.modalCancel();
            ci.cancel();
        }
    }
}
