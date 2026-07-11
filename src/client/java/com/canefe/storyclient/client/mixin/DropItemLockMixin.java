package com.canefe.storyclient.client.mixin;

import com.canefe.storyclient.client.interaction.InteractionLock;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Blocks dropping items (drop key / drop-stack) while
 * {@link InteractionLock#getLocked()} is true — the drop key is polled directly
 * off {@code options.dropKey}, bypassing the attack/use paths, so it needs its
 * own gate at {@link ClientPlayerEntity#dropSelectedItem(boolean)}.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class DropItemLockMixin {

    @Inject(method = "dropSelectedItem(Z)Z", at = @At("HEAD"), cancellable = true)
    private void storyclient$lockDrop(boolean entireStack, CallbackInfoReturnable<Boolean> cir) {
        if (InteractionLock.INSTANCE.getLocked()) cir.setReturnValue(false);
    }
}
