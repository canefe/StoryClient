package com.canefe.storyclient.client.mixin;

import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.BooleanSupplier;

/**
 * Exposes {@link DownloadingTerrainScreen}'s private {@code shouldClose} supplier
 * and {@code worldEntryReason} so {@link com.canefe.storyclient.client.screen.BlackFadeTerrainScreen}
 * can reconstruct a black-out variant that reuses vanilla's exact dismissal lifecycle
 * (the screen closes itself once {@code shouldClose} returns true and the minimum
 * load time has elapsed).
 */
@Mixin(DownloadingTerrainScreen.class)
public interface DownloadingTerrainScreenAccessor {

    @Accessor("shouldClose")
    BooleanSupplier storyclient$getShouldClose();

    @Accessor("worldEntryReason")
    DownloadingTerrainScreen.WorldEntryReason storyclient$getWorldEntryReason();
}
