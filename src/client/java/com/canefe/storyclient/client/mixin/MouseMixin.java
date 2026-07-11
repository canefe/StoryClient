package com.canefe.storyclient.client.mixin;

import com.canefe.storyclient.client.camera.OocCameraController;
import com.canefe.storyclient.client.cinematic.SpawnCinematicController;
import com.canefe.storyclient.client.wheel.ActionWheelHud;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts mouse motion + scroll for client camera modes:
 * <ul>
 *   <li>Action wheel open → deltas drive the wheel highlight, look suppressed.</li>
 *   <li>Spawn cinematic active → look fully locked so the swoop isn't disturbed.</li>
 *   <li>OOC camera active → deltas orbit the camera and scroll zooms, both
 *       consumed so the body doesn't turn.</li>
 * </ul>
 */
@Mixin(Mouse.class)
public abstract class MouseMixin {
    @Shadow private double cursorDeltaX;
    @Shadow private double cursorDeltaY;

    @Inject(method = "updateMouse", at = @At("HEAD"), cancellable = true)
    private void storyclient$wheelInterceptUpdateMouse(CallbackInfo ci) {
        // Lock look during the spawn cinematic so the swoop isn't disturbed.
        if (SpawnCinematicController.INSTANCE.isActive()) {
            this.cursorDeltaX = 0.0;
            this.cursorDeltaY = 0.0;
            ci.cancel();
            return;
        }
        // OOC: route look deltas into the orbit, don't turn the body.
        if (OocCameraController.INSTANCE.isActive()) {
            OocCameraController.INSTANCE.onMouseDelta(this.cursorDeltaX, this.cursorDeltaY);
            this.cursorDeltaX = 0.0;
            this.cursorDeltaY = 0.0;
            ci.cancel();
            return;
        }
        if (ActionWheelHud.INSTANCE.getOpen()) {
            ActionWheelHud.INSTANCE.onMouseDelta(this.cursorDeltaX, this.cursorDeltaY);
            this.cursorDeltaX = 0.0;
            this.cursorDeltaY = 0.0;
            ci.cancel();
        }
    }

    @Inject(method = "onMouseScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void storyclient$oocInterceptScroll(
        long window, double horizontal, double vertical, CallbackInfo ci
    ) {
        // OOC: scroll zooms the orbit instead of scrolling the hotbar.
        if (OocCameraController.INSTANCE.isActive()) {
            OocCameraController.INSTANCE.onScroll(vertical);
            ci.cancel();
        }
    }
}
