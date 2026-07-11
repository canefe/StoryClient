package com.canefe.storyclient.client.mixin;

import com.canefe.storyclient.client.screen.BlackFadeTerrainScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces vanilla's "Loading terrain..." dirt screen (shown when joining a
 * server or changing worlds) with a fade-to-black screen.
 *
 * <p>Intercepts {@link MinecraftClient#setScreen(Screen)}: when the incoming
 * screen is a plain {@link DownloadingTerrainScreen}, we swap in a
 * {@link BlackFadeTerrainScreen} built from the original's {@code shouldClose}
 * supplier and {@code worldEntryReason} so the dismissal lifecycle is identical —
 * MC still decides when the screen closes; only the visuals change.
 *
 * <p>The {@code BlackFadeTerrainScreen instanceof} guard prevents infinite
 * recursion: our replacement is itself a {@code DownloadingTerrainScreen}, so the
 * re-issued {@code setScreen} call must pass straight through.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientTerrainScreenMixin {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void storyclient$replaceTerrainScreen(Screen screen, CallbackInfo ci) {
        if (screen instanceof DownloadingTerrainScreen && !(screen instanceof BlackFadeTerrainScreen)) {
            DownloadingTerrainScreenAccessor accessor = (DownloadingTerrainScreenAccessor) screen;
            BlackFadeTerrainScreen replacement = new BlackFadeTerrainScreen(
                accessor.storyclient$getShouldClose(),
                accessor.storyclient$getWorldEntryReason()
            );
            ((MinecraftClient) (Object) this).setScreen(replacement);
            ci.cancel();
        }
    }
}
