package eu.client.mixins;

import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderState.class)
public class GuiRenderStateMixin {

    @Shadow private int firstStratumAfterBlur;

    @Inject(method = "blurBeforeThisStratum", at = @At("HEAD"), cancellable = true)
    private void euclient$noopIfAlreadyBlurredThisFrame(CallbackInfo ci) {
        if (firstStratumAfterBlur != Integer.MAX_VALUE) {
            ci.cancel();
        }
    }
}
