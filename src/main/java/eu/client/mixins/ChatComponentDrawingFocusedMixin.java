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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// BetterChatModule "Animation" (slide-in for new messages). handleMessage's real body (verified
// via javap) calls ActiveTextCollector.accept(LEFT, x=0 (hardcoded constant), y=textTop,
// this.parameters, message) -- x is NEVER derived from graphics.pose() here, and `parameters` is
// a field snapshotted earlier (once per render pass, not per line), so translating pose() inside
// this method (the original approach) had zero effect on where the line actually draws -- it
// "worked" per the diagnostic logs (offset computed correctly) while visually doing nothing (the
// "chỉ hiện cái rụp" report). Modify the literal x argument of that accept() call directly instead
// -- computed at HEAD (has the real FormattedCharSequence param) and stashed in a field since
// @ModifyArg's own handler only sees the one int argument being replaced.
@Mixin(targets = "net.minecraft.client.gui.components.ChatComponent$DrawingFocusedGraphicsAccess")
public class ChatComponentDrawingFocusedMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("EUClient/BetterChat");

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
        LOGGER.info("[BetterChat] slide-in x offset={}", euclient$xOffset);
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
