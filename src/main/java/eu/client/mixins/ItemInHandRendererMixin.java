package eu.client.mixins;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import eu.client.EUClient;
import eu.client.mixins.accessors.LevelRendererAccessor;
import eu.client.modules.impl.visuals.ShadersModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// ShadersModule's Outline auto-applying to the first-person view-model, no separate "Hands"
// toggle -- ItemInHandRenderer only ever renders the LOCAL player's own hands/held item (other
// entities' held items go through the unrelated ItemInHandLayer instead), so gating on
// isValidEntity(mc.player) is exactly "did the user's own Targets filters (Players, ...) opt
// themselves in", same as any other entity.
//
// Only Outline is reachable here: ItemStackRenderState.submit(...)'s last param IS an
// outlineColor, the same glow-through-walls mechanism as entities, just plumbed one call
// shallower. There's no tintedColor equivalent for items (they render through
// submitItem(..., tints, quads, foilType), where "tints" is baked-in per-quad color, not a
// global recolor multiply) -- Fill for Hands would mean recoloring every submitted quad's
// vertex color by hand, a separate and much bigger job. Left as a follow-up.
//
// A BetterChams-style OutlineBufferSource.getBuffer() redirect (spanning the whole
// renderHandsWithItems window) was tried and REVERTED (2026-08-08): renderAllFeatures(), called at
// the tail of that same method, is a DEFERRED flush for OTHER entities' queued feature geometry too
// (capes, equipped items, etc via the same SubmitNodeStorage), not just this player's own hands --
// there's no way to tell "this getBuffer call is for my hand" from "this getBuffer call is some
// other entity's queued Chams silhouette flushing at the same moment" using only that coarse
// in-this-method flag, so it hijacked other entities' Chams draws into the wrong place and made
// world Chams disappear entirely. Reverted to just the outlineColor computation below -- narrower,
// doesn't touch anything outside this player's own item submission.
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("EUClient/Shaders");
    private static long euclient$lastLog = 0L;
    private static int euclient$logCount = 0;
    private static String euclient$currentItem = "?";

    // TEMP DIAGNOSTIC (2026-08-08): "tay này có thì tay kia mất" still reported after the
    // OutlineBufferSource guard fix -- log which item/hand computed which color, to see if BOTH
    // hands actually reach here with a real color each, or if one hand's @ModifyArg never fires /
    // computes 0. Remove once root-caused.
    @Inject(method = "renderItem", at = @At("HEAD"))
    private void euclient$logRenderItem(net.minecraft.world.entity.LivingEntity mob, net.minecraft.world.item.ItemStack itemStack, net.minecraft.world.item.ItemDisplayContext type, PoseStack poseStack, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        euclient$currentItem = itemStack.isEmpty() ? "empty" : (itemStack.getItem() + "@" + type);
    }

    // Re-added (2026-08-08) alongside the OutlineBufferSource guard, not instead of it: the guard
    // stops the CRASH (IllegalStateException aborting the flush), this flag+redirect is the actual
    // BetterChams trick that forces hand geometry onto the outline target regardless of which
    // internal path queued it. Read by OutlineBufferSourceMixin.
    @Inject(method = "renderHandsWithItems", at = @At("HEAD"))
    private void euclient$startHands(float partialTick, PoseStack pose, SubmitNodeCollector collector, LocalPlayer player, int light, CallbackInfo ci) {
        eu.client.utils.mixins.HandsRenderState.renderingHands = true;
    }

    @ModifyArg(
            method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"),
            index = 4
    )
    private int euclient$handsOutline(int outlineColor) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return outlineColor;

        ShadersModule shaders = EUClient.MODULE_MANAGER.getModule(ShadersModule.class);
        // No shouldOutline() gate: Fill piggybacks on this SAME capture (outline.fsh's Fill branch
        // just re-emits whatever landed in the captured silhouette buffer), so outlineColor has to
        // get set whenever Fill OR Outline is active -- gating on shouldOutline() alone left pure
        // Fill mode with nothing captured to fill, i.e. the held item rendered nothing at all.
        // getFillColor() (not getColor().getRGB()) to force full alpha same as EntityRendererMixin --
        // the captured vertex alpha has to be 255 regardless of the configured Color's own alpha,
        // or FillOpacity gets applied twice (real alpha here, then FillOpacity again in the shader).
        // Same Shaders > Chams > PopChams priority as ShadersModule.pickActiveOutlineChain() picks
        // for the WORLD's shared outline chain -- hands only ever checked Shaders before, so
        // Chams/PopChams being on never touched the first-person view-model at all ("Chams không
        // hoạt động cho Hands").
        int result = outlineColor;
        String source = "none";
        if (shaders.isToggled() && shaders.isValidEntity(mc.player)) {
            result = shaders.getFillColor(mc.player);
            source = "Shaders";
        } else {
            eu.client.modules.impl.visuals.ChamsModule chams = EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.visuals.ChamsModule.class);
            if (chams.isToggled() && chams.players.getValue() && chams.hands.getValue()) {
                result = chams.getHandsFillColor(mc.player);
                source = "Chams";
            } else {
                eu.client.modules.impl.visuals.PopChamsModule popChams = EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.visuals.PopChamsModule.class);
                if (popChams.isToggled()) {
                    result = popChams.fillColor.getColor().getRGB() | 0xFF000000;
                    source = "PopChams";
                }
            }
        }

        long now = System.currentTimeMillis();
        if (now - euclient$lastLog > 500) { euclient$lastLog = now; euclient$logCount = 0; }
        if (euclient$logCount++ < 6) LOGGER.info("[HandsOutline] item={} source={} color={}", euclient$currentItem, source, Integer.toHexString(result));

        return result;
    }

    // Bobbing-desync root cause (verified via javap on GameRenderer/ItemInHandRenderer/
    // OutlineBufferSource/LevelRenderer): the hand's outline vertices ARE generated during
    // renderAllFeatures() (called just above, inside this same method) into the shared
    // OutlineBufferSource, but nothing flushes them here -- vanilla's own
    // outlineBufferSource().endOutlineBatch() call happens exactly once per frame, inside
    // LevelRenderer.renderLevel's main pass, which already finished by the time this method
    // (called from GameRenderer.renderItemInHand, strictly AFTER renderLevel) runs. Left alone,
    // those vertices sit buffered until NEXT frame's renderLevel flushes them -- under NEXT
    // frame's world projection (bob baked in) and view-rotation matrix instead of this frame's
    // hud projection (no bob) the vertices were actually built under. That mismatch, compounding
    // frame-to-frame while the camera keeps moving, is the entire "outline lags/desyncs from the
    // hand while moving" symptom. Flush right here instead, at RETURN -- still inside
    // renderItemInHand's own modelViewStack push and while the hud projection is still bound.
    //
    // entityOutlineTarget() (the public method) returns null here: LevelRenderer.renderLevel
    // clears its FrameGraph resource handles (targets.clear()) before returning, and that's what
    // the public getter reads. The private field itself is never null after the client's initial
    // resource-reload (LevelRenderer.initOutline() runs once, unconditionally, from
    // onResourceManagerReload) -- reach it via LevelRendererAccessor instead of calling
    // initOutline() again ourselves (it unconditionally destroys+recreates the target; calling it
    // here every frame was the prior, reverted attempt's actual crash cause).
    @Inject(method = "renderHandsWithItems", at = @At("RETURN"))
    private void euclient$flushHandOutline(float partialTick, PoseStack pose, SubmitNodeCollector collector, LocalPlayer player, int light, CallbackInfo ci) {
        eu.client.utils.mixins.HandsRenderState.renderingHands = false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer == null) return;
        RenderTarget outline = ((LevelRendererAccessor) mc.levelRenderer).euclient$getEntityOutlineTarget();
        if (outline == null) return;

        // OutputTarget.OUTLINE_TARGET falls back to the MAIN render target when
        // entityOutlineTarget() reads null (see above) -- without this override,
        // endOutlineBatch() here would paint the raw (unprocessed) silhouette straight onto the
        // screen instead of into the dedicated outline target. Scoped to this one flush call only.
        RenderSystem.outputColorTextureOverride = outline.getColorTextureView();
        RenderSystem.outputDepthTextureOverride = outline.getDepthTextureView();
        mc.renderBuffers().outlineBufferSource().endOutlineBatch();
        RenderSystem.outputColorTextureOverride = null;
        RenderSystem.outputDepthTextureOverride = null;
    }
}
