package com.canefe.storyclient.client.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link HandledScreen}'s protected {@code x}/{@code y} panel-origin
 * layout fields. They are declared on {@code HandledScreen}, so shadowing them
 * from an {@code InventoryScreen}-targeted mixin fails at load time ("field x
 * was not located"); accessing them via an accessor on the declaring class is
 * the reliable route. Used by {@link InventoryCraftingHiderMixin} to position the
 * paint-over that hides the baked-in crafting-grid outlines.
 */
@Mixin(HandledScreen.class)
public interface HandledScreenLayoutAccessor {
    @Accessor("x")
    int storyclient$getX();

    @Accessor("y")
    int storyclient$getY();
}
