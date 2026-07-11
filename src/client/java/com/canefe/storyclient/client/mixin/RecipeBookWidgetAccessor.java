package com.canefe.storyclient.client.mixin;

import net.minecraft.client.gui.screen.recipebook.RecipeBookWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@link RecipeBookWidget}'s protected {@code setOpen} so the survival
 * inventory can persistently close the book (see {@link InventoryCraftingHiderMixin}).
 *
 * <p>Setting the private {@code open} field directly does NOT hold: the widget's
 * {@code update()} (run every {@code handledScreenTick}) restores open-state from
 * the persisted per-category {@code RecipeBookOptions}. {@code setOpen(false)}
 * also writes that persisted flag ({@code recipeBook.setGuiOpen(category, false)}),
 * so once invoked the book stays closed.
 */
@Mixin(RecipeBookWidget.class)
public interface RecipeBookWidgetAccessor {
    @Invoker("setOpen")
    void storyclient$invokeSetOpen(boolean opened);
}
