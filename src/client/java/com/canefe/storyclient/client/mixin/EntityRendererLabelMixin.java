package com.canefe.storyclient.client.mixin;

import com.canefe.storyclient.client.wheel.NearbyNPCCache;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suppresses the vanilla nametag for any entity StoryClient knows about
 * (i.e. anything in {@link NearbyNPCCache}). The Helix-style nametag renderer
 * draws the replacement.
 *
 * <p>We hook {@code LivingEntityRenderer.shouldShowName} (the same vector
 * sodium-extra uses for its "Hide Mob/Player Nametags" toggle) — returning
 * false short-circuits both the call to {@code renderLabelIfPresent} and the
 * "hasLabel" predicate. Cleaner than mixing into the render call site.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class EntityRendererLabelMixin {
    @Inject(method = "hasLabel(Lnet/minecraft/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
    private void storyclient$suppressVanillaLabel(
        LivingEntity entity,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (NearbyNPCCache.INSTANCE.get(entity.getUuid()) != null) {
            cir.setReturnValue(false);
        }
    }
}
