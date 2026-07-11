package com.canefe.storyclient.client.mixin;

import com.canefe.storyclient.client.interaction.InteractionLock;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suppresses world interaction while {@link InteractionLock#getLocked()} is true
 * (spawn cinematic, OOC spectator camera, or sim-paused). Freezing movement input
 * does NOT stop these — attack, item-use, and block-breaking run through their own
 * {@link MinecraftClient} paths (mouse-held breaking especially), so we cancel
 * them at the source. Drop is gated separately in {@code DropItemLockMixin}.
 */
@Mixin(MinecraftClient.class)
public abstract class InteractionLockMixin {

    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void storyclient$lockAttack(CallbackInfoReturnable<Boolean> cir) {
        if (InteractionLock.INSTANCE.getLocked()) cir.setReturnValue(false);
    }

    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void storyclient$lockItemUse(CallbackInfo ci) {
        if (InteractionLock.INSTANCE.getLocked()) ci.cancel();
    }

    @Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void storyclient$lockBlockBreaking(boolean breaking, CallbackInfo ci) {
        if (InteractionLock.INSTANCE.getLocked()) ci.cancel();
    }
}
