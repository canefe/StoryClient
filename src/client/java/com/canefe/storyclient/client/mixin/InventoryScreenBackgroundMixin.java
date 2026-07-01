package com.canefe.storyclient.client.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Removes the translucent black background dim behind the survival inventory (E).
 *
 * <p>When a {@link Screen} is opened in-world, {@code renderInGameBackground}
 * draws the blur + darkening gradient that dims the world. That method lives on
 * {@link Screen}, so we mixin {@code Screen} (where the descriptor resolves) and
 * cancel ONLY when {@code this} is the {@link InventoryScreen} — leaving the
 * world (and the left/right ImGui Health/Skills windows that frame the inventory)
 * fully visible. Every other screen (pause menu, chat, other containers) keeps
 * vanilla darkening.
 */
@Mixin(Screen.class)
public abstract class InventoryScreenBackgroundMixin {

    @Inject(method = "renderInGameBackground", at = @At("HEAD"), cancellable = true)
    private void storyclient$skipInventoryDarkening(DrawContext context, CallbackInfo ci) {
        if ((Object) this instanceof InventoryScreen) {
            ci.cancel();
        }
    }
}
