package com.canefe.storyclient.client.mixin;

import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes protected {@link Camera} mutators so client effects can drive the
 * camera per frame: the combat-shake mixin applies yaw/pitch deltas, and the
 * spawn-cinematic mixin overrides full position + rotation during the swoop.
 */
@Mixin(Camera.class)
public interface CameraAccessor {
    @Invoker("setRotation")
    void storyclient$setRotation(float yaw, float pitch);

    @Invoker("setPos")
    void storyclient$setPos(Vec3d pos);

    @Invoker("getYaw")
    float storyclient$getYaw();

    @Invoker("getPitch")
    float storyclient$getPitch();

    /**
     * Forces third-person rendering so the player's own model draws while the
     * spawn-cinematic camera is detached up in the sky. The entity stays grounded;
     * only the camera moves. Also suppresses the first-person hand render.
     */
    @Accessor("thirdPerson")
    void storyclient$setThirdPerson(boolean thirdPerson);
}
