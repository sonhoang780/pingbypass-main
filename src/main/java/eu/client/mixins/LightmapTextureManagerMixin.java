package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.visuals.FullBrightModule;
import eu.client.modules.impl.visuals.NoRenderModule;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// ponytail: Lightmap was rewritten from a TextureTarget-framebuffer approach into a GPU
// texture + uniform-buffer + render-pass one (LightmapRenderState carries the raw factors that
// used to only be reachable by clearing/redrawing the framebuffer). Overriding the render-state
// fields directly before the render pass runs achieves the same "full bright" / "no darkness"
// effect without needing a framebuffer to clear.
@Mixin(Lightmap.class)
public class LightmapTextureManagerMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void euclient$render(LightmapRenderState renderState, CallbackInfo info) {
        FullBrightModule fullBright = EUClient.MODULE_MANAGER.getModule(FullBrightModule.class);
        if (fullBright.isToggled() && fullBright.mode.getValue().equalsIgnoreCase("Gamma")) {
            renderState.blockFactor = 1.0f;
            renderState.skyFactor = 1.0f;
            renderState.brightness = 1.0f;
            renderState.needsUpdate = true;
        }

        NoRenderModule noRender = EUClient.MODULE_MANAGER.getModule(NoRenderModule.class);
        if (noRender.isToggled() && noRender.blindness.getValue()) {
            renderState.darknessEffectScale = 0.0f;
            renderState.needsUpdate = true;
        }
    }
}
