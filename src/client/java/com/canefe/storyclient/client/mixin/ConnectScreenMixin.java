package com.canefe.storyclient.client.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blacks out the "Connecting to the server…" screen (the network-connect phase
 * shown before the world loads, with the Cancel button).
 *
 * <p>Unlike the terrain screen, {@link ConnectScreen} owns live connection state
 * ({@code connection}/{@code future} and the cancel handler), so we must NOT
 * replace the screen instance — only its visuals. We cancel the original
 * {@code render} (which would draw the blurred background, status text, and
 * Cancel button) and paint solid black instead. The connection machinery and
 * {@code tick()} keep running underneath, so the screen still advances to the
 * world / terrain-load phase exactly as vanilla would.
 *
 * <p>Trade-off: this also hides the Cancel button. That is intentional per the
 * "seamless black transition" goal; the connect still aborts on its own failure
 * paths and on disconnect.
 */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin extends Screen {

    protected ConnectScreenMixin(net.minecraft.text.Text title) {
        super(title);
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void storyclient$blackoutConnectScreen(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        // Skyrim-style loading screen (random art + tip) instead of plain black.
        // begin() is idempotent, so the terrain phase continues the same screen.
        com.canefe.storyclient.client.screen.LoadingScreenRenderer.INSTANCE.render(context, this.width, this.height, 1.0f);
        ci.cancel();
    }
}
