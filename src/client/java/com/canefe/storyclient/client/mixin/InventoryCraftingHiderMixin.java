package com.canefe.storyclient.client.mixin;

import com.canefe.storyclient.client.inventory.InventoryWidgetView;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.recipebook.RecipeBookWidget;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Removes the 2x2 crafting grid and the recipe book from the survival inventory
 * (E) screen. Client-only cosmetic change: no {@link ScreenHandler} / server
 * protocol is touched, so nothing can desync and items can't be lost.
 *
 * <p>Two surgical moves, all scoped to {@link InventoryScreen}:
 * <ol>
 *   <li><b>Recipe book:</b> {@link #storyclient$dropRecipeButton} drops the
 *       recipe-book toggle button, and {@link #storyclient$forceBookClosed} calls
 *       the real {@code setOpen(false)} at {@code init} TAIL. This persists: the
 *       book's {@code update()} (run every {@code handledScreenTick}) restores
 *       open-state from the per-category {@code RecipeBookOptions}, and
 *       {@code setOpen(false)} writes that same persisted flag, so it stays shut.
 *       (Setting the {@code open} field directly does NOT hold — {@code update()}
 *       re-opens it from the still-true persisted flag next tick.)</li>
 *   <li><b>Crafting slots:</b> {@link #storyclient$hideCraftingSlots} shoves the
 *       five crafting slots (result index 0 + the 2x2 input, indices 1-4) far
 *       off-screen at TAIL of {@code init()}. Off-screen slots vanilla-render
 *       nowhere and can't be clicked or hovered; they stay empty since the
 *       handler is never modified.</li>
 * </ol>
 *
 * <p><b>Leftover outlines:</b> the 2x2 grid + result slot outlines are baked
 * into the inventory background <i>texture</i>, so moving the {@link Slot}
 * objects hides the interactive slots but not the drawn outlines.
 * {@link #storyclient$paintOverCraftingArea} fills that region with the panel's
 * flat field colour at TAIL of {@code drawBackground} to erase them. The colour
 * is tuned to the active resource pack (Excalibur: {@code 0xFF382A25}); a pack
 * with a different / gradient inventory panel would need this constant retuned.
 */
@Mixin(InventoryScreen.class)
public abstract class InventoryCraftingHiderMixin {

    @Shadow
    private RecipeBookWidget recipeBook;

    /** Flat panel field colour of the active inventory texture (Excalibur pack). */
    private static final int STORYCLIENT_PANEL_COLOR = 0xFF382A25;

    // Panel-local bounds of the grid + result outlines to erase. Measured from
    // the Excalibur inventory.png: grid outlines occupy x=95..172, y=15..54;
    // beyond x=172 the panel content ends (transparent), so do not overrun it.
    private static final int STORYCLIENT_CRAFT_X = 95;
    private static final int STORYCLIENT_CRAFT_Y = 15;
    private static final int STORYCLIENT_CRAFT_W = 76;   // 95 -> 171
    private static final int STORYCLIENT_CRAFT_H = 40;   // 15 -> 55 (both rows)

    // Corner-widget render bounds (panel-local). Independent of the paint-over
    // rect so the content can sit further left than the erased grid outlines.
    private static final int STORYCLIENT_WIDGET_X = 88;
    private static final int STORYCLIENT_WIDGET_Y = 17;
    private static final int STORYCLIENT_WIDGET_W = 84;   // 88 -> 172
    private static final int STORYCLIENT_WIDGET_H = 38;

    @Redirect(
        method = "init",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/ingame/InventoryScreen;addDrawableChild(Lnet/minecraft/client/gui/Element;)Lnet/minecraft/client/gui/Element;"
        )
    )
    private Element storyclient$dropRecipeButton(InventoryScreen screen, Element child) {
        // The only addDrawableChild call directly in InventoryScreen.init() is
        // the recipe-book toggle button (super.init() calls are not touched by a
        // method-scoped redirect). Return the widget WITHOUT registering it so
        // the toggle is gone.
        return child;
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void storyclient$forceBookClosed(CallbackInfo ci) {
        // Persistently close the recipe book. setOpen(false) also writes the
        // per-category RecipeBookOptions flag, so update() (every tick) keeps it
        // closed instead of restoring the saved open state.
        ((RecipeBookWidgetAccessor) this.recipeBook).storyclient$invokeSetOpen(false);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void storyclient$hideCraftingSlots(CallbackInfo ci) {
        ScreenHandler handler = ((HandledScreen<?>) (Object) this).getScreenHandler();
        if (!(handler instanceof PlayerScreenHandler)) {
            return;
        }
        // Slots 0 (result) + 1-4 (2x2 input) are the crafting slots on the
        // player screen handler. Push them well off-screen.
        for (int i = 0; i <= 4; i++) {
            Slot slot = handler.slots.get(i);
            SlotAccessor acc = (SlotAccessor) slot;
            acc.storyclient$setX(-9999);
            acc.storyclient$setY(-9999);
        }
    }

    @Inject(method = "drawBackground", at = @At("TAIL"))
    private void storyclient$paintOverCraftingArea(DrawContext context, float delta, int mouseX, int mouseY, CallbackInfo ci) {
        HandledScreenLayoutAccessor layout = (HandledScreenLayoutAccessor) this;
        int left = layout.storyclient$getX() + STORYCLIENT_CRAFT_X;
        int top = layout.storyclient$getY() + STORYCLIENT_CRAFT_Y;
        context.fill(left, top, left + STORYCLIENT_CRAFT_W, top + STORYCLIENT_CRAFT_H, STORYCLIENT_PANEL_COLOR);
    }

    @Inject(method = "drawForeground", at = @At("TAIL"))
    private void storyclient$renderCornerWidget(DrawContext context, int mouseX, int mouseY, CallbackInfo ci) {
        // drawForeground coordinates are panel-local (see the title draw), so pass
        // the crafting-rect constants directly. Drawn here (above slots) so item
        // icons render on top.
        InventoryWidgetView.INSTANCE.render(
            context, STORYCLIENT_WIDGET_X, STORYCLIENT_WIDGET_Y, STORYCLIENT_WIDGET_W, STORYCLIENT_WIDGET_H);
    }
}
