package com.canefe.storyclient.client.mixin;

import com.canefe.storyclient.client.camera.OocCameraController;
import com.canefe.storyclient.client.confrontation.ConfrontationCameraController;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the out-of-character (OOC) orbit camera by overriding the camera
 * transform at the tail of {@link Camera#update}, after vanilla has positioned
 * it. While {@link OocCameraController#isActive()} is true we replace position +
 * rotation with the controller's orbit around the player's body, and force
 * third-person so the grounded model renders (and the first-person hand hides).
 *
 * <p>Mirrors {@code SpawnCinematicCameraMixin}; the two never overlap because
 * {@link OocCameraController#setActive} refuses to activate while the spawn
 * cinematic owns the camera.
 */
@Mixin(Camera.class)
public abstract class OocCameraMixin {

    @Inject(
        method = "update(Lnet/minecraft/world/BlockView;Lnet/minecraft/entity/Entity;ZZF)V",
        at = @At("TAIL")
    )
    private void storyclient$applyOocCamera(
        BlockView area,
        Entity focusedEntity,
        boolean thirdPerson,
        boolean inverseView,
        float tickDelta,
        CallbackInfo ci
    ) {
        if (!OocCameraController.INSTANCE.isActive()) return;

        Vec3d pos = OocCameraController.INSTANCE.cameraPos(tickDelta);
        Float yaw = OocCameraController.INSTANCE.cameraYaw();
        Float pitch = OocCameraController.INSTANCE.cameraPitch();
        if (pos == null || yaw == null || pitch == null) return;

        CameraAccessor self = (CameraAccessor) this;
        // Detach in the air while the body stays grounded — third-person renders
        // the player model and hides the first-person hand.
        self.storyclient$setThirdPerson(true);
        self.storyclient$setRotation(yaw, pitch);
        self.storyclient$setPos(pos);
    }

    /**
     * Two-shot confrontation camera: frame both participants from a point back
     * off their midpoint. Close-up shots use {@code setCameraEntity} instead and
     * need no transform override, so this only fires in two-shot mode.
     */
    @Inject(
        method = "update(Lnet/minecraft/world/BlockView;Lnet/minecraft/entity/Entity;ZZF)V",
        at = @At("TAIL")
    )
    private void storyclient$applyConfrontationCamera(
        BlockView area,
        Entity focusedEntity,
        boolean thirdPerson,
        boolean inverseView,
        float tickDelta,
        CallbackInfo ci
    ) {
        if (!ConfrontationCameraController.INSTANCE.isTwoShot()) return;

        Vec3d pos = ConfrontationCameraController.INSTANCE.cameraPos(tickDelta);
        Float yaw = ConfrontationCameraController.INSTANCE.cameraYaw();
        Float pitch = ConfrontationCameraController.INSTANCE.cameraPitch();
        if (pos == null || yaw == null || pitch == null) return;

        CameraAccessor self = (CameraAccessor) this;
        self.storyclient$setThirdPerson(true);
        self.storyclient$setRotation(yaw, pitch);
        self.storyclient$setPos(pos);
    }
}
