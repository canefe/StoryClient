package com.canefe.storyclient.client.mixin;

import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the {@code x}/{@code y} pixel position of a {@link Slot} so the
 * survival-inventory crafting slots can be shoved off-screen (see
 * {@link InventoryCraftingHiderMixin}). The fields are {@code final} (only the
 * constructor sets them), so the setters need {@link Mutable} to strip {@code
 * final} — without it a {@code putfield} on a final field throws
 * {@link IllegalAccessError} at runtime.
 */
@Mixin(Slot.class)
public interface SlotAccessor {
    @Mutable
    @Accessor("x")
    void storyclient$setX(int x);

    @Mutable
    @Accessor("y")
    void storyclient$setY(int y);

    @Accessor("x")
    int storyclient$getX();
}
