package com.canefe.storyclient.client.mixin;

import net.minecraft.network.ClientConnection;
import io.netty.channel.ChannelHandlerContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ConcurrentModificationException;

/**
 * Prevents ConcurrentModificationException from disconnecting the client.
 * This is a vanilla Minecraft race condition where entity updates arrive
 * while the render thread iterates the entity list.
 */
@Mixin(ClientConnection.class)
public class ClientConnectionMixin {
    @Inject(method = "exceptionCaught", at = @At("HEAD"), cancellable = true)
    private void onExceptionCaught(ChannelHandlerContext context, Throwable ex, CallbackInfo ci) {
        if (ex instanceof ConcurrentModificationException) {
            // Suppress this — it's a harmless race condition, not a real error
            ci.cancel();
        }
    }
}
