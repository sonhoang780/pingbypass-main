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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("EUClient/Shaders");
    private static long euclient$lastLog = 0L;

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
        if (shaders.isToggled() && shaders.isValidEntity(mc.player)) {
            return shaders.getFillColor(mc.player);
        }

        eu.client.modules.impl.visuals.ChamsModule chams = EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.visuals.ChamsModule.class);
        if (chams.isToggled() && chams.players.getValue() && chams.hands.getValue()) {
            return chams.getHandsFillColor(mc.player);
        }

        eu.client.modules.impl.visuals.PopChamsModule popChams = EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.visuals.PopChamsModule.class);
        if (popChams.isToggled()) {
            return popChams.fillColor.getColor().getRGB() | 0xFF000000;
        }

        return outlineColor;
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
