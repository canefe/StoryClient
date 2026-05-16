package com.canefe.storyclient.client.mixin;

import com.canefe.storyclient.client.combat.CombatCameraEffects;
import net.minecraft.client.render.Camera;
import net.minecraft.world.BlockView;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies directional-combat screen-shake by mutating Camera rotation at the
 * tail of {@link Camera#update}. Offsets come from {@link CombatCameraEffects}
 * and are zero outside an active stagger/parry-receive event.
 */
@Mixin(Camera.class)
public abstract class CameraShakeMixin {
    @Inject(
        method = "update(Lnet/minecraft/world/BlockView;Lnet/minecraft/entity/Entity;ZZF)V",
        at = @At("TAIL")
    )
    private void storyclient$applyCombatShake(
        BlockView area,
        Entity focusedEntity,
        boolean thirdPerson,
        boolean inverseView,
        float tickDelta,
        CallbackInfo ci
    ) {
        float yawOffset = CombatCameraEffects.INSTANCE.currentShakeYawOffset();
        float pitchOffset = CombatCameraEffects.INSTANCE.currentShakePitchOffset();
        if (yawOffset == 0f && pitchOffset == 0f) return;
        CameraAccessor self = (CameraAccessor) this;
        self.storyclient$setRotation(self.storyclient$getYaw() + yawOffset, self.storyclient$getPitch() + pitchOffset);
    }
}
