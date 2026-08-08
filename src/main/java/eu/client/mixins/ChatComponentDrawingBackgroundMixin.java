package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.miscellaneous.BetterChatModule;
import eu.client.utils.animations.Easing;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Same as ChatComponentDrawingFocusedMixin (see its comment) -- the background/click-through pass
// needs the same x-argument fix, not a pose translate.
@Mixin(targets = "net.minecraft.client.gui.components.ChatComponent$DrawingBackgroundGraphicsAccess")
public class ChatComponentDrawingBackgroundMixin {
    @Unique private int euclient$xOffset = 0;

    @Inject(method = "handleMessage", at = @At("HEAD"))
    private void euclient$computeOffset(int textTop, float opacity, FormattedCharSequence message, CallbackInfoReturnable<Boolean> cir) {
        euclient$xOffset = 0;
        BetterChatModule module = EUClient.MODULE_MANAGER.getModule(BetterChatModule.class);
        if (!module.isToggled() || !module.animation.getValue()) return;

        Long start = module.getAnimationStart(message);
        if (start == null) return;

        int delay = module.delay.getValue().intValue();
        float progress = delay <= 0 ? 1f : Easing.toDelta(start, delay);
        if (progress >= 1f) return;

        float width = net.minecraft.client.Minecraft.getInstance().font.width(message);
        euclient$xOffset = Math.round(-width * (1f - progress));
    }

    @ModifyArg(
            method = "handleMessage",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/ActiveTextCollector;accept(Lnet/minecraft/client/gui/TextAlignment;IILnet/minecraft/client/gui/ActiveTextCollector$Parameters;Lnet/minecraft/util/FormattedCharSequence;)V"),
            index = 1
    )
    private int euclient$slideInX(int x) {
        return x + euclient$xOffset;
    }
}
