package com.canefe.storyclient.client.mixin;

import com.canefe.storyclient.client.panel.StoryTabsPanel;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Draws the native (DrawContext) Health and Skills panels flanking the survival
 * inventory (E), replacing the old floating imgui windows. Health sits on the
 * LEFT, Skills on the RIGHT, both top-aligned with the inventory.
 *
 * <p>We mixin {@link HandledScreen} (where the {@code x}/{@code backgroundWidth}
 * layout fields live) and act only when {@code this} is the
 * {@link InventoryScreen}, so container GUIs are untouched. Rendering is at the
 * TAIL of {@code render} (above the inventory + its un-dimmed world background,
 * see {@link InventoryScreenBackgroundMixin}); Tend-button clicks are routed via
 * a {@code mouseClicked} inject into {@link HealthNativeView#clickAt}.
 */
@Mixin(HandledScreen.class)
public abstract class InventorySkillsPanelMixin {

    @Shadow
    protected int x;
    @Shadow
    protected int y;
    @Shadow
    protected int backgroundWidth;

    @Inject(method = "render", at = @At("TAIL"))
    private void storyclient$renderStoryPanels(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!((Object) this instanceof InventoryScreen)) {
            return;
        }
        // Single tabbed panel (Health / Skills / …) hugging the inventory's left
        // edge, 8px gap. Tab icons live in its title bar.
        int leftX = this.x - 8 - StoryTabsPanel.WIDTH;
        StoryTabsPanel.INSTANCE.render(context, leftX, this.y, mouseX, mouseY);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void storyclient$panelClick(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button != 0 || !((Object) this instanceof InventoryScreen)) {
            return;
        }
        // Tab-icon clicks switch tabs; body clicks (Health Tend buttons) route to
        // the active view. Consume the click if the panel handled it.
        if (StoryTabsPanel.INSTANCE.clickAt((int) mouseX, (int) mouseY)) {
            cir.setReturnValue(true);
        }
    }
}
