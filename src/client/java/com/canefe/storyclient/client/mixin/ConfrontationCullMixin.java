package com.canefe.storyclient.client.mixin;

import com.canefe.storyclient.client.confrontation.ConfrontationCameraController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * During a LOCKED confrontation, skips rendering any entity that is NOT the
 * current shot's subject and is close enough to the camera to occlude the framed
 * character. Keeps the confrontation shot clean when NPCs stand near the lens.
 *
 * <p>Injected at the head of {@link EntityRenderer#render} and cancellable, so a
 * culled entity is simply not drawn this frame (the lightweight alternative to a
 * full translucent-render pipeline).
 */
@Mixin(EntityRenderer.class)
public abstract class ConfrontationCullMixin {

    @Inject(
        method = "render(Lnet/minecraft/entity/Entity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void storyclient$cullOccluders(
        Entity entity,
        float yaw,
        float tickDelta,
        net.minecraft.client.util.math.MatrixStack matrices,
        net.minecraft.client.render.VertexConsumerProvider vertexConsumers,
        int light,
        CallbackInfo ci
    ) {
        if (!ConfrontationCameraController.INSTANCE.isActive()) return;
        Vec3d camPos = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
        if (ConfrontationCameraController.INSTANCE.shouldCull(entity, camPos)) {
            ci.cancel();
        }
    }
}
