package com.canefe.storyclient.client.mixin;

import com.canefe.storyclient.client.camera.OocCameraController;
import com.canefe.storyclient.client.cinematic.SpawnCinematicController;
import com.canefe.storyclient.client.pause.PauseState;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freezes player movement while the simulation is paused (story:pause_state).
 *
 * Targets {@link KeyboardInput} (the player's actual input impl), NOT the base
 * {@link Input}: KeyboardInput.tick OVERRIDES Input.tick, so injecting into the
 * base method never runs for the real player. After vanilla computes movement
 * from the keys each tick, we zero it out: no walk, no strafe, no jump, no
 * sneak. Camera look is left untouched (handled by Mouse) so the player can
 * still look around while frozen — only locomotion is suspended.
 */
@Mixin(KeyboardInput.class)
public abstract class PlayerFreezeMixin {
    @Inject(method = "tick(ZF)V", at = @At("TAIL"))
    private void storyclient$freezeWhilePaused(boolean slowDown, float slowDownFactor, CallbackInfo ci) {
        if (!PauseState.INSTANCE.getPaused()
            && !SpawnCinematicController.INSTANCE.isActive()
            && !OocCameraController.INSTANCE.isActive()) return;
        Input self = (Input) (Object) this;
        self.movementForward = 0.0f;
        self.movementSideways = 0.0f;
        self.jumping = false;
        self.sneaking = false;
        self.pressingForward = false;
        self.pressingBack = false;
        self.pressingLeft = false;
        self.pressingRight = false;
    }
}
