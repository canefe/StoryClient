package com.canefe.storyclient.client.mixin;

import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(ChatHud.class)
public interface ChatHudAccessor {
    @Accessor("messages")
    List<ChatHudLine> getMessages();

    @Accessor("visibleMessages")
    List<ChatHudLine.Visible> getVisibleMessages();

    @Accessor("messages")
    void setMessages(List<ChatHudLine> messages);

    @Accessor("visibleMessages")
    void setVisibleMessages(List<ChatHudLine.Visible> visibleMessages);

    // Use correct method name - might be refreshMessages, refresh, etc.
    @Invoker("refresh")
    void refreshChatMessages();
}
