package eu.client.managers;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.TickEvent;
import eu.client.mixins.accessors.ChatHudAccessor;
import eu.client.modules.impl.core.CommandsModule;
import eu.client.modules.impl.miscellaneous.BetterChatModule;
import eu.client.utils.IMinecraft;
import eu.client.utils.chat.ChatUtils;
import eu.client.utils.mixins.IChatHudLine;
import eu.client.utils.mixins.IChatHudLineVisible;
import eu.client.utils.text.FormattingUtils;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

public class ChatManager implements IMinecraft {
    private final List<String> awaitMessages = new ArrayList<>();

    public ChatManager() {
        EUClient.EVENT_HANDLER.subscribe(this);
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.gui == null) return;
        if (awaitMessages.isEmpty()) return;

        for (String message : new ArrayList<>(awaitMessages)) {
            addMessage(message);
            awaitMessages.remove(message);
        }
    }

    public void message(String message) {
        if (mc.player == null || mc.gui == null) return;
        addMessage(getWatermark() + " " + ChatUtils.getSecondary() + message);
    }

    public void message(String message, String identifier) {
        if (mc.player == null || mc.gui == null) return;
        deleteMessage(identifier);
        addMessage(getWatermark() + " " + ChatUtils.getSecondary() + message, identifier);
    }

    public void tagged(String message, String tag) {
        if (mc.player == null || mc.gui == null) return;
        addMessage(getWatermark() + ChatFormatting.DARK_AQUA + " [" + ChatFormatting.AQUA + tag + ChatFormatting.DARK_AQUA + "]: " + ChatUtils.getSecondary() + message);
    }

    public void tagged(String message, String tag, String identifier) {
        if (mc.player == null || mc.gui == null) return;
        deleteMessage(identifier);
        addMessage(getWatermark() + ChatFormatting.DARK_AQUA + " [" + ChatFormatting.AQUA + tag + ChatFormatting.DARK_AQUA + "]: " + ChatUtils.getSecondary() + message, identifier);
    }

    public void info$await(String message) {
        awaitMessages.add(getWatermark() + ChatFormatting.DARK_BLUE + " [" + ChatFormatting.BLUE + "?" + ChatFormatting.DARK_BLUE + "] " + ChatUtils.getSecondary() + message);
    }

    public void info(String message) {
        if (mc.player == null || mc.gui == null) return;
        addMessage(getWatermark() + ChatFormatting.DARK_BLUE + " [" + ChatFormatting.BLUE + "?" + ChatFormatting.DARK_BLUE + "] " + ChatUtils.getSecondary() + message);
    }

    public void warn$await(String message) {
        awaitMessages.add(getWatermark() + ChatFormatting.GOLD + " [" + ChatFormatting.YELLOW + "!" + ChatFormatting.GOLD + "] " + ChatUtils.getSecondary() + message);
    }

    public void warn(String message) {
        if (mc.player == null || mc.gui == null) return;
        addMessage(getWatermark() + ChatFormatting.GOLD + " [" + ChatFormatting.YELLOW + "!" + ChatFormatting.GOLD + "] " + ChatUtils.getSecondary() + message);
    }

    public void error$await(String message) {
        awaitMessages.add(getWatermark() + ChatFormatting.DARK_RED + " [" + ChatFormatting.RED + "!!" + ChatFormatting.DARK_RED + "] " + ChatUtils.getSecondary() + message);
    }

    public void error(String message) {
        if (mc.player == null || mc.gui == null) return;
        addMessage(getWatermark() + ChatFormatting.DARK_RED + " [" + ChatFormatting.RED + "!!" + ChatFormatting.DARK_RED + "] " + ChatUtils.getSecondary() + message);
    }

    public void await(String message) {
        awaitMessages.add(getWatermark() + " " + ChatUtils.getSecondary() + message);
    }

    public void await(String message, String tag) {
        awaitMessages.add(getWatermark() + ChatFormatting.DARK_AQUA + " [" + ChatFormatting.AQUA + tag + ChatFormatting.DARK_AQUA + "]: " + ChatUtils.getSecondary() + message);
    }

    public void addMessage(String message) {
        addTextMessage(Component.literal(message), "");
    }

    public void addMessage(String message, String identifier) {
        addTextMessage(Component.literal(message), identifier);
    }

    private void addTextMessage(Component message, String identifier) {
        // Font glyph baking uploads to the GPU and RenderSystem hard-asserts render-thread
        // ownership in 26.1.2. Callers can reach this from off-thread contexts (e.g. packet
        // listeners run synchronously on the Netty IO thread), so hop to the render thread.
        if (!mc.isSameThread()) {
            mc.execute(() -> addTextMessage(message, identifier));
            return;
        }

        GuiMessage line = new GuiMessage(mc.gui.getGuiTicks(), message, null, GuiMessageSource.SYSTEM_CLIENT, GuiMessageTag.system());

        ((IChatHudLine) (Object) line).euclient$setClientMessage(true);
        ((IChatHudLine) (Object) line).euclient$setClientIdentifier(identifier);

        ((ChatHudAccessor) mc.gui.getChat()).invokeLogChatMessage(line);
        ((ChatHudAccessor) mc.gui.getChat()).invokeAddMessage(line);

        List<FormattedCharSequence> list = ComponentRenderUtils.wrapComponents(line.content(), Mth.floor(((ChatHudAccessor) mc.gui.getChat()).invokeGetWidth() / ((ChatHudAccessor) mc.gui.getChat()).invokeGetScale()), mc.font);
        for (int j = 0; j < list.size(); ++j) {
            FormattedCharSequence orderedText = list.get(j);

            if (mc.gui.getChat().isChatFocused() && ((ChatHudAccessor) mc.gui.getChat()).getScrolledLines() > 0) {
                ((ChatHudAccessor) mc.gui.getChat()).setHasUnreadNewMessages(true);
                mc.gui.getChat().scrollChat(1);
            }

            boolean bl2 = j == list.size() - 1;

            GuiMessage.Line visible = new GuiMessage.Line(line, orderedText, bl2);

            ((IChatHudLineVisible) (Object) visible).euclient$setClientMessage(true);
            ((IChatHudLineVisible) (Object) visible).euclient$setClientIdentifier(identifier);

            ((ChatHudAccessor) mc.gui.getChat()).getVisibleMessages().addFirst(visible);
            if (EUClient.MODULE_MANAGER.getModule(BetterChatModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(BetterChatModule.class).animation.getValue()) EUClient.MODULE_MANAGER.getModule(BetterChatModule.class).getAnimationMap().put(visible, System.currentTimeMillis());
        }

        while (((ChatHudAccessor) mc.gui.getChat()).getVisibleMessages().size() > 100) {
            ((ChatHudAccessor) mc.gui.getChat()).getVisibleMessages().removeLast();
        }
    }

    public static void deleteMessage(String identifier) {
        try {
            ArrayList<GuiMessage> removedLines = new ArrayList<>();
            for (GuiMessage message : ((ChatHudAccessor) mc.gui.getChat()).getMessages()) {
                if (!((IChatHudLine) (Object) message).euclient$isClientMessage() || ((IChatHudLine) (Object) message).euclient$getClientIdentifier().isEmpty()) continue;
                if (((IChatHudLine) (Object) message).euclient$getClientIdentifier().equals(identifier)) {
                    removedLines.add(message);
                }
            }

            ArrayList<GuiMessage.Line> removedVisibleLines = new ArrayList<>();
            for (GuiMessage.Line message : ((ChatHudAccessor) mc.gui.getChat()).getVisibleMessages()) {
                if (!((IChatHudLineVisible) (Object) message).euclient$isClientMessage() || ((IChatHudLineVisible) (Object) message).euclient$getClientIdentifier().isEmpty()) continue;
                if (((IChatHudLineVisible) (Object) message).euclient$getClientIdentifier().equals(identifier)) {
                    removedVisibleLines.add(message);
                }
            }

            ((ChatHudAccessor) mc.gui.getChat()).getMessages().removeAll(removedLines);
            ((ChatHudAccessor) mc.gui.getChat()).getVisibleMessages().removeAll(removedVisibleLines);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    private String getWatermark() {
        return getWatermark(null);
    }

    private String getWatermark(String text) {
        if (!EUClient.MODULE_MANAGER.getModule(CommandsModule.class).watermark.getValue()) return "";
        return FormattingUtils.getFormatting(EUClient.MODULE_MANAGER.getModule(CommandsModule.class).secondaryWatermarkColor.getValue()) + EUClient.MODULE_MANAGER.getModule(CommandsModule.class).opening.getValue() + FormattingUtils.getFormatting(EUClient.MODULE_MANAGER.getModule(CommandsModule.class).primaryWatermarkColor.getValue()) + (text == null ? EUClient.MODULE_MANAGER.getModule(CommandsModule.class).watermarkText.getValue() : text) + FormattingUtils.getFormatting(EUClient.MODULE_MANAGER.getModule(CommandsModule.class).secondaryWatermarkColor.getValue()) + EUClient.MODULE_MANAGER.getModule(CommandsModule.class).closing.getValue();
    }
}
