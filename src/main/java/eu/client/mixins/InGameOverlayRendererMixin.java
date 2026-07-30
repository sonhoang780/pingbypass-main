package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.visuals.NoRenderModule;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class InGameOverlayRendererMixin {
    @Inject(method = "extractCameraOverlays", at = @At("HEAD"), cancellable = true)
    private void euclient$extractCameraOverlays(GuiGraphicsExtractor context, DeltaTracker deltaTracker, CallbackInfo info) {
        NoRenderModule noRender = EUClient.MODULE_MANAGER.getModule(NoRenderModule.class);
        if (noRender.isToggled() && (noRender.fireOverlay.getValue() || noRender.blockOverlay.getValue() || noRender.liquidOverlay.getValue())) {
            info.cancel();
        }
    }
}
