package com.canefe.storyclient.client.mixin;

import com.canefe.storyclient.client.cinematic.SpawnCinematicController;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the GTA-style spawn swoop by overriding the camera transform at the tail
 * of {@link Camera#update}, after vanilla has positioned it at the player's eyes.
 * While {@link SpawnCinematicController#isActive()} is true we replace position +
 * rotation with the controller's eased birds-eye → first-person path; once the
 * cinematic ends the override stops and vanilla's transform stands unchanged.
 *
 * <p>Runs after {@code CameraShakeMixin} only by registration order, but the two
 * never overlap in practice (no combat shake during a spawn cinematic).
 */
@Mixin(Camera.class)
public abstract class SpawnCinematicCameraMixin {

    @Inject(
        method = "update(Lnet/minecraft/world/BlockView;Lnet/minecraft/entity/Entity;ZZF)V",
        at = @At("TAIL")
    )
    private void storyclient$applySpawnCinematic(
        BlockView area,
        Entity focusedEntity,
        boolean thirdPerson,
        boolean inverseView,
        float tickDelta,
        CallbackInfo ci
    ) {
        if (!SpawnCinematicController.INSTANCE.isActive()) return;

        Vec3d pos = SpawnCinematicController.INSTANCE.cameraPos(tickDelta);
        Float yaw = SpawnCinematicController.INSTANCE.cameraYaw();
        Float pitch = SpawnCinematicController.INSTANCE.cameraPitch();
        if (pos == null || yaw == null || pitch == null) return;

        CameraAccessor self = (CameraAccessor) this;
        // Render the grounded player model (and hide the first-person hand) by
        // marking the camera third-person — the camera is detached in the sky
        // while the entity stays put on the ground.
        self.storyclient$setThirdPerson(true);
        self.storyclient$setRotation(yaw, pitch);
        self.storyclient$setPos(pos);
    }
}
