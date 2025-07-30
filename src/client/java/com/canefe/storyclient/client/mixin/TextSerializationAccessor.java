package com.canefe.storyclient.client.mixin;

import com.google.gson.JsonElement;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Text.Serialization.class)
public interface TextSerializationAccessor {
    @Invoker("fromJson")
    static MutableText callFromJson(JsonElement json, RegistryWrapper.WrapperLookup registries) {
        throw new AssertionError();
    }
}