package com.canefe.storyclient.client.screen;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;

import java.util.function.BooleanSupplier;

/**
 * Drop-in replacement for vanilla's "Loading terrain..." dirt screen shown when
 * joining a server or changing worlds. Subclasses {@link DownloadingTerrainScreen}
 * so it inherits the entire dismissal lifecycle (tick / close / shouldClose +
 * minimum-load-time gating) unchanged — we only override rendering to paint solid
 * the Skyrim-style loading screen instead of the dirt background and label.
 *
 * <p>The loading screen is handled upstream too: {@code ConnectScreenMixin} shows
 * the same {@link LoadingScreenRenderer} during the connect phase, so the art +
 * tip carry over seamlessly into this screen. On {@link #close()} (terrain ready,
 * world now loaded) we hand off to {@link ScreenFadeOverlay}, which fades the
 * loading image away over the live world.
 *
 * <p>Constructed by {@code MinecraftClientTerrainScreenMixin}, which forwards the
 * original screen's private {@code shouldClose} supplier and {@code worldEntryReason}
 * (read via {@link com.canefe.storyclient.client.mixin.DownloadingTerrainScreenAccessor}).
 */
public class BlackFadeTerrainScreen extends DownloadingTerrainScreen {

    public BlackFadeTerrainScreen(BooleanSupplier shouldClose, WorldEntryReason worldEntryReason) {
        super(shouldClose, worldEntryReason);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // Intentionally do NOT call super — that would draw the dirt/panorama background.
        // Skyrim-style loading art + tip (continuous from the connect phase).
        LoadingScreenRenderer.INSTANCE.render(context, this.width, this.height, 1.0f);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Loading screen; skip super.render(), which would draw the "Loading terrain" label.
        this.renderBackground(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        // World is loaded and the HUD is about to render — start the reveal fade
        // and kick off the GTA-style birds-eye → first-person spawn swoop.
        ScreenFadeOverlay.INSTANCE.beginFadeOut();
        com.canefe.storyclient.client.cinematic.SpawnCinematicController.INSTANCE.start();
        super.close();
    }
}
