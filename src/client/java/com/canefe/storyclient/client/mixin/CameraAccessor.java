package com.canefe.storyclient.client.mixin;

import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes Camera#setRotation so the combat-shake mixin can apply per-frame yaw/pitch deltas. */
@Mixin(Camera.class)
public interface CameraAccessor {
    @Invoker("setRotation")
    void storyclient$setRotation(float yaw, float pitch);

    @Invoker("getYaw")
    float storyclient$getYaw();

    @Invoker("getPitch")
    float storyclient$getPitch();
}
