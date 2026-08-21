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
    // Re-added (2026-08-08) alongside the OutlineBufferSource guard, not instead of it: the guard
    // stops the CRASH (IllegalStateException aborting the flush), this flag+redirect is the actual
    // BetterChams trick that forces hand geometry onto the outline target regardless of which
    // internal path queued it. Read by OutlineBufferSourceMixin.
    // PORT (26.2): renderHandsWithItems -> submitHandsWithItems -- real crash caught via runtime
    // test (MixinApplyError, "No refMap loaded" / target not found), same signature otherwise
    // (confirmed via real ItemInHandRenderer.java source).
    @Inject(method = "submitHandsWithItems", at = @At("HEAD"))
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

        return result;
    }

    // PORT (26.2): the bobbing-desync fix below is OBSOLETE, not ported -- it manually flushed the
    // OLD OutlineBufferSource-queued hand-outline geometry at the right point in the frame
    // (RenderBuffers.outlineBufferSource()/endOutlineBatch() -- confirmed via javap on the real
    // jar, RenderBuffers has neither method anymore). 26.2's outline mechanism is a different
    // architecture entirely: outlineColor is now a direct param on submitModel/submitItem/etc,
    // folded into vanilla's own SubmitNodeCollection -> FeatureRenderer batching (see this
    // session's port audit doc, category #4/OutlineBufferSourceMixin), flushed automatically by
    // vanilla itself, not something this mod manually queues/flushes anymore. Whether the ORIGINAL
    // bobbing-desync symptom this worked around still exists under the new architecture is
    // UNCONFIRMED -- needs real runtime testing (gradlew.bat runClient, hands outlined via Chams/
    // Shaders, move around) before assuming it's fine.
    @Inject(method = "submitHandsWithItems", at = @At("RETURN"))
    private void euclient$flushHandOutline(float partialTick, PoseStack pose, SubmitNodeCollector collector, LocalPlayer player, int light, CallbackInfo ci) {
        eu.client.utils.mixins.HandsRenderState.renderingHands = false;
    }
}
