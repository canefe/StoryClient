package com.canefe.storyclient.client.mixin;

import com.canefe.storyclient.client.wheel.ActionWheelHud;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When the action wheel is open, capture raw mouse deltas for the wheel's
 * highlight selection and suppress the player's look rotation for that frame.
 */
@Mixin(Mouse.class)
public abstract class MouseMixin {
    @Shadow private double cursorDeltaX;
    @Shadow private double cursorDeltaY;

    @Inject(method = "updateMouse", at = @At("HEAD"), cancellable = true)
    private void storyclient$wheelInterceptUpdateMouse(CallbackInfo ci) {
        if (ActionWheelHud.INSTANCE.getOpen()) {
            ActionWheelHud.INSTANCE.onMouseDelta(this.cursorDeltaX, this.cursorDeltaY);
            this.cursorDeltaX = 0.0;
            this.cursorDeltaY = 0.0;
            ci.cancel();
        }
    }
}
