package com.canefe.storyclient.client.mixin;

import com.canefe.storyclient.client.TypingManager;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onGameMessage", at = @At("HEAD"), cancellable = true)
    private void onGameMessage(GameMessageS2CPacket packet, CallbackInfo ci) {
        Text message = packet.content();
        String rawText = message.getString();
        // Check if this is an NPC typing message
        if (rawText.contains("<npc_typing>")) {
            System.out.println("DEBUG: Found typing tag, passing to manager");
            // Pass to our TypingManager
            TypingManager.INSTANCE.onIncomingServerMessage(rawText);
            // Cancel the vanilla chat message handling
            ci.cancel();
            System.out.println("DEBUG: Message handling cancelled");
        }
        else if (rawText.contains("<npc_typing_end>")) {
            System.out.println("DEBUG: Found typing end tag, passing to manager");
            // Pass to our TypingManager
            TypingManager.INSTANCE.onIncomingServerMessage(rawText);
            // Cancel the vanilla chat message handling
            ci.cancel();
            System.out.println("DEBUG: Message handling cancelled");
        }
    }
}