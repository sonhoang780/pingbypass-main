package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.core.CommandsModule;
import eu.client.modules.impl.miscellaneous.BetterChatModule;
import eu.client.utils.mixins.IChatHudLineVisible;
import eu.client.utils.text.FormattingUtils;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.ChatFormatting;
import org.joml.Matrix3x2f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/**
 * PORT (26.1.2): ChatComponent's render is no longer a single overridable method -- it's a
 * retained-mode extractRenderState(ChatGraphicsAccess,...) built around private nested
 * ChatGraphicsAccess implementations (DrawingFocusedGraphicsAccess/DrawingBackgroundGraphicsAccess),
 * each an anonymous-adjacent private static class. Per-line text recolor (rainbow/client formatting)
 * and the indicator/tag draw calls live inside a private LineConsumer anonymous class that Mixin
 * can't cleanly retarget without fragile synthetic-method-name guessing, so those two BetterChat
 * features (noIndicators, rainbow/client text recolor) are dropped here -- ponytail: revisit if/when
 * this needs runtime verification against the real jar's compiled anonymous-class layout.
 * Reimplemented instead via clean extension points that ARE stable across the retained-mode rewrite:
 * updatePose (X offset), the two OptionInstance<Double> opacity reads (text alpha / background
 * opacity), and the addMessage/addMessageToDisplayQueue hooks (timestamps, animation-map tracking).
 */
@Mixin(ChatComponent.class)
public abstract class ChatHudMixin {
    @Shadow @Final private List<GuiMessage.Line> trimmedMessages;

    @Inject(method = "addMessageToDisplayQueue", at = @At("TAIL"))
    private void euclient$trackAnimation(GuiMessage message, CallbackInfo ci) {
        BetterChatModule module = EUClient.MODULE_MANAGER.getModule(BetterChatModule.class);
        if (module.isToggled() && module.animation.getValue() && !this.trimmedMessages.isEmpty()) {
            module.getAnimationMap().put(this.trimmedMessages.getFirst(), System.currentTimeMillis());
        }
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V", at = @At("TAIL"))
    private void euclient$cleanupAnimations(CallbackInfo ci) {
        BetterChatModule module = EUClient.MODULE_MANAGER.getModule(BetterChatModule.class);
        if (module.isToggled() && module.animation.getValue()) {
            module.getAnimationMap().entrySet().removeIf(entry -> System.currentTimeMillis() - entry.getValue() > module.delay.getValue().intValue());
        }
    }

    @Redirect(method = "extractRenderState(Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;IILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;updatePose(Ljava/util/function/Consumer;)V"))
    private void euclient$applyOffset(ChatComponent.ChatGraphicsAccess instance, Consumer<Matrix3x2f> updater) {
        BetterChatModule module = EUClient.MODULE_MANAGER.getModule(BetterChatModule.class);
        int offset = module.isToggled() ? module.offset.getValue().intValue() : 0;
        instance.updatePose(pose -> {
            updater.accept(pose);
            if (offset != 0) pose.translate(offset, 0.0F);
        });
    }

    @Redirect(method = "extractRenderState(Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;IILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;", ordinal = 0))
    private Object euclient$textOpacity(net.minecraft.client.OptionInstance<?> instance) {
        BetterChatModule module = EUClient.MODULE_MANAGER.getModule(BetterChatModule.class);
        if (module.isToggled()) {
            return module.textAlpha.getValue().doubleValue() / 255.0;
        }
        return instance.get();
    }

    @Redirect(method = "extractRenderState(Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;IILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;", ordinal = 1))
    private Object euclient$backgroundOpacity(net.minecraft.client.OptionInstance<?> instance) {
        BetterChatModule module = EUClient.MODULE_MANAGER.getModule(BetterChatModule.class);
        if (module.isToggled()) {
            return switch (module.background.getValue()) {
                case "Clear" -> 0.0;
                case "Custom" -> module.color.getColor().getAlpha() / 255.0;
                default -> instance.get();
            };
        }
        return instance.get();
    }

    @ModifyArgs(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/chat/GuiMessage;<init>(ILnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V"))
    private void addMessage(Args args, Component message, MessageSignature signature, GuiMessageSource source, GuiMessageTag indicator) {
        BetterChatModule module = EUClient.MODULE_MANAGER.getModule(BetterChatModule.class);
        if (module.isToggled() && module.timestamps.getValue()) {
            args.set(1, Component.literal("").append(Component.literal(FormattingUtils.getFormatting(EUClient.MODULE_MANAGER.getModule(CommandsModule.class).secondaryWatermarkColor.getValue()) + module.opening.getValue() + FormattingUtils.getFormatting(EUClient.MODULE_MANAGER.getModule(CommandsModule.class).primaryWatermarkColor.getValue()) + new SimpleDateFormat("HH:mm").format(new Date()) + FormattingUtils.getFormatting(EUClient.MODULE_MANAGER.getModule(CommandsModule.class).secondaryWatermarkColor.getValue()) + module.closing.getValue() + ChatFormatting.RESET + " ")).append(message));
        }
    }
}
