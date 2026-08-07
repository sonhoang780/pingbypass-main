package eu.client.mixins;

import com.mojang.blaze3d.vertex.PoseStack;
import eu.client.EUClient;
import eu.client.modules.impl.visuals.NoRenderModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// NoRenderModule FireOverlay/BlockOverlay/LiquidOverlay -- the real target (confirmed via
// javap against the actual 26.1.2 client jar, method names/signatures match). Two earlier
// guesses for BlockOverlay (chunk-mesh air substitution) and the fire animation's separate
// per-entity flame layer both looked plausible but were live-tested wrong -- these are plain
// first-person screen-space texture overlays drawn by ScreenEffectRenderer, same as meteor-
// client's own NoRender hooks the same three methods for the same three settings.
@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectRendererMixin {
    @Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
    private static void euclient$renderFire(PoseStack poseStack, MultiBufferSource bufferSource, TextureAtlasSprite sprite, CallbackInfo ci) {
        NoRenderModule module = EUClient.MODULE_MANAGER.getModule(NoRenderModule.class);
        if (module.isToggled() && module.fireOverlay.getValue()) ci.cancel();
    }

    @Inject(method = "renderWater", at = @At("HEAD"), cancellable = true)
    private static void euclient$renderWater(Minecraft minecraft, PoseStack poseStack, MultiBufferSource bufferSource, CallbackInfo ci) {
        NoRenderModule module = EUClient.MODULE_MANAGER.getModule(NoRenderModule.class);
        if (module.isToggled() && module.liquidOverlay.getValue()) ci.cancel();
    }

    @Inject(method = "renderTex", at = @At("HEAD"), cancellable = true)
    private static void euclient$renderTex(TextureAtlasSprite sprite, PoseStack poseStack, MultiBufferSource bufferSource, CallbackInfo ci) {
        NoRenderModule module = EUClient.MODULE_MANAGER.getModule(NoRenderModule.class);
        if (module.isToggled() && module.blockOverlay.getValue()) ci.cancel();
    }
}
