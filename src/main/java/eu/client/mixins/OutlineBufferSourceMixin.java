package eu.client.mixins;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import eu.client.EUClient;
import eu.client.modules.impl.visuals.ChamsModule;
import eu.client.modules.impl.visuals.PopChamsModule;
import eu.client.modules.impl.visuals.ShadersModule;
import eu.client.utils.mixins.HandsRenderState;
import eu.client.utils.mixins.NoopVertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Two separate fixes layered on the same hook, both needed:
//
// 1) The guard (verified against the actual decompiled 26.1.2 source): getBuffer(RenderType)
// throws IllegalStateException("Can't render an outline for this rendertype!") whenever the
// RenderType has no outline companion. ModelFeatureRenderer (entities) already guards this before
// calling getBuffer; ItemFeatureRenderer (held items -- hands) does NOT, so a held BLOCK item's
// solid RenderType threw mid-flush and aborted whatever else that same renderAllFeatures() call
// hadn't gotten to yet that frame (world Chams "biến mất"). Silently no-op instead of throwing.
//
// 2) The BetterChams-style redirect (example-addon-master's MixinItemInHandRenderer/
// MixinOutlineBufferSource pair, re-added per request 2026-08-08): even with the crash gone, one
// hand's outline still didn't reliably land in the actual outline render target. For the span
// hands are being rendered (HandsRenderState.renderingHands, set by ItemInHandRendererMixin),
// force WHERE the GPU writes around every getBuffer call -- guarantees hand geometry lands on the
// outline target whenever Shaders/Chams/PopChams wants it outlined, independent of whichever
// internal path queued it. Gated on euclient$handsWantOutline() so it never touches a frame where
// nothing should be outlined at all.
@Mixin(OutlineBufferSource.class)
public class OutlineBufferSourceMixin {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("EUClient/Shaders");
    private static long euclient$lastLog = 0L;
    private static int euclient$logCount = 0;

    @Inject(method = "getBuffer", at = @At("HEAD"), cancellable = true)
    private void euclient$onGetBuffer(RenderType renderType, CallbackInfoReturnable<VertexConsumer> cir) {
        if (!renderType.isOutline() && renderType.outline().isEmpty()) {
            // TEMP DIAGNOSTIC (2026-08-08): confirm whether this guard is what's actually skipping
            // one hand's outline (a real per-RenderType limitation) vs something else. Remove once
            // confirmed.
            long now = System.currentTimeMillis();
            if (now - euclient$lastLog > 500) { euclient$lastLog = now; euclient$logCount = 0; }
            if (euclient$logCount++ < 6) LOGGER.info("[OutlineGuard] SKIPPED renderType={}", renderType);
            cir.setReturnValue(NoopVertexConsumer.INSTANCE);
            return;
        }

        if (!HandsRenderState.renderingHands || !euclient$handsWantOutline()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer == null) return;
        RenderTarget outline = ((eu.client.mixins.accessors.LevelRendererAccessor) mc.levelRenderer).euclient$getEntityOutlineTarget();
        if (outline == null) return;

        RenderSystem.outputColorTextureOverride = outline.getColorTextureView();
        RenderSystem.outputDepthTextureOverride = outline.getDepthTextureView();
    }

    @Inject(method = "getBuffer", at = @At("RETURN"))
    private void euclient$onGetBufferReturn(RenderType renderType, CallbackInfoReturnable<VertexConsumer> cir) {
        if (!HandsRenderState.renderingHands || !euclient$handsWantOutline()) return;
        RenderSystem.outputColorTextureOverride = null;
        RenderSystem.outputDepthTextureOverride = null;
    }

    private static boolean euclient$handsWantOutline() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;

        ShadersModule shaders = EUClient.MODULE_MANAGER.getModule(ShadersModule.class);
        if (shaders.isToggled() && shaders.isValidEntity(mc.player)) return true;

        ChamsModule chams = EUClient.MODULE_MANAGER.getModule(ChamsModule.class);
        if (chams.isToggled() && chams.players.getValue() && chams.hands.getValue()) return true;

        PopChamsModule popChams = EUClient.MODULE_MANAGER.getModule(PopChamsModule.class);
        return popChams.isToggled();
    }
}
