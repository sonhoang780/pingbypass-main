package eu.client.mixins.accessors;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(ChatComponent.class)
public interface ChatHudAccessor {
    @Accessor("allMessages")
    List<GuiMessage> getMessages();

    @Accessor("trimmedMessages")
    List<GuiMessage.Line> getVisibleMessages();

    @Accessor("chatScrollbarPos")
    int getScrolledLines();

    @Accessor("newMessageSinceScroll")
    void setHasUnreadNewMessages(boolean hasUnreadNewMessages);

    @Invoker("logChatMessage")
    void invokeLogChatMessage(GuiMessage message);

    @Invoker("addMessageToDisplayQueue")
    void invokeAddVisibleMessage(GuiMessage message);

    @Invoker("addMessageToQueue")
    void invokeAddMessage(GuiMessage message);

    @Invoker("getWidth")
    int invokeGetWidth();

    @Invoker("getScale")
    double invokeGetScale();
}
