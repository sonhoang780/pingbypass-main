package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.visuals.NoRenderModule;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    // Reset NoRender's per-frame item counter once at the very start of each frame's level render.
    // renderLevel runs exactly once per frame; without this the counter accumulates across frames
    // and quickly exceeds the limit, culling every item permanently.
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void euclient$resetItemCounter(CallbackInfo info) {
        NoRenderModule noRender = EUClient.MODULE_MANAGER.getModule(NoRenderModule.class);
        if (noRender != null) noRender.resetItemCounter();
    }
}