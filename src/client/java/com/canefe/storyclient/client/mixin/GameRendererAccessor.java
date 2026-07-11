package com.canefe.storyclient.client.mixin;

import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@link GameRenderer}'s private post-effect plumbing so the spawn
 * cinematic can swap in its own grade and push per-frame uniforms. Both
 * {@code loadPostProcessor} and {@code disablePostProcessor} are private in
 * 1.21.1, and {@code postProcessor} is a private field, so all three are reached
 * via mixin here rather than the public API.
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Invoker("loadPostProcessor")
    void storyclient$loadPostProcessor(Identifier id);

    @Invoker("disablePostProcessor")
    void storyclient$disablePostProcessor();

    @Accessor("postProcessor")
    PostEffectProcessor storyclient$getPostProcessor();
}
