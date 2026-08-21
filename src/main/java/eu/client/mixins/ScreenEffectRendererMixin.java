package eu.client.mixins;

import com.mojang.blaze3d.vertex.PoseStack;
import eu.client.EUClient;
import eu.client.modules.impl.visuals.NoRenderModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
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
//
// PORT (26.2): renderFire/renderWater/renderTex (MultiBufferSource-param methods) are GONE --
// real 26.2 source (ScreenEffectRenderer.java, read in full) restructured the whole class around
// SubmitNodeCollector.submitCustomGeometry(...) instead of a direct buffer-source draw. The three
// call sites still exist 1:1 under new names/params, called from the new submit(...) entry point:
// submitFire(PoseStack, SubmitNodeCollector, TextureAtlasSprite) = old renderFire (fire overlay),
// submitWater(Minecraft, PoseStack, SubmitNodeCollector) = old renderWater (liquid overlay),
// submitBlockSprite(TextureAtlasSprite, PoseStack, SubmitNodeCollector, int) = old renderTex
// (view-blocking block overlay, now takes an extra baked-in tint color param vanilla always passed
// -15132391 for -- irrelevant to us, we just cancel before it runs).
@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectRendererMixin {
    @Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
    private static void euclient$renderFire(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, TextureAtlasSprite sprite, CallbackInfo ci) {
        NoRenderModule module = EUClient.MODULE_MANAGER.getModule(NoRenderModule.class);
        if (module.isToggled() && module.fireOverlay.getValue()) ci.cancel();
    }

    @Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
    private static void euclient$renderWater(Minecraft minecraft, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
        NoRenderModule module = EUClient.MODULE_MANAGER.getModule(NoRenderModule.class);
        if (module.isToggled() && module.liquidOverlay.getValue()) ci.cancel();
    }

    @Inject(method = "submitBlockSprite", at = @At("HEAD"), cancellable = true)
    private static void euclient$renderTex(TextureAtlasSprite sprite, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int color, CallbackInfo ci) {
        NoRenderModule module = EUClient.MODULE_MANAGER.getModule(NoRenderModule.class);
        if (module.isToggled() && module.blockOverlay.getValue()) ci.cancel();
    }
}
