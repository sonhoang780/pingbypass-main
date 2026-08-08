package eu.client.mixins;

import com.mojang.blaze3d.vertex.PoseStack;
import eu.client.EUClient;
import eu.client.modules.impl.visuals.ShadersModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// ShadersModule "Hands" for the EMPTY (no item) first-person hand -- AvatarRenderer.renderHand
// (the sole caller of ItemInHandRenderer.renderPlayerArm, first-person self hand only, verified
// via javap) submits the bare arm through the SHORT submitModelPart(part, pose, type, light,
// overlay, sprite) overload, which hardcodes outlineColor=0 (see OrderedSubmitNodeCollector's
// default-method bytecode: every short overload forwards a literal 0 to the real 11-arg abstract
// method's trailing outlineColor param). Redirecting straight to that 11-arg overload with a real
// color plumbs the exact same glow-through-walls mechanism items already get -- no custom JFA
// pipeline needed, submitModelPart supports outline natively, ItemInHandRenderer just never wired
// one through for the itemless case.
@Mixin(AvatarRenderer.class)
public class AvatarRendererMixin {
    // require = 0: some launch environments (e.g. ViaFabricPlus-heavy modpacks) carry another mod
    // that also transforms AvatarRenderer.renderHand ahead of this one, removing/altering this exact
    // call before euclient's mixin runs -- "Scanned 0 target(s)" at injection time, otherwise a hard
    // game-crash (verified via javap that the real 26.1.2 bytecode itself DOES contain this exact
    // call; this is environment-specific mixin ordering, not a euclient bug). Missing the injection
    // just means first-person Hands outline silently doesn't apply there instead of crashing.
    @Redirect(
            method = "renderHand",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModelPart(Lnet/minecraft/client/model/geom/ModelPart;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IILnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V"),
            require = 0
    )
    private void euclient$handOutline(SubmitNodeCollector collector, ModelPart part, PoseStack pose, RenderType type, int light, int overlay, TextureAtlasSprite sprite) {
        collector.submitModelPart(part, pose, type, light, overlay, sprite, false, false, -1, null, euclient$outlineColor());
    }

    private static int euclient$outlineColor() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        ShadersModule shaders = EUClient.MODULE_MANAGER.getModule(ShadersModule.class);
        // No shouldOutline() gate: Fill piggybacks on this SAME capture (outline.fsh's Fill branch
        // just re-emits whatever landed in the captured silhouette buffer), so outlineColor has to
        // get set whenever Fill OR Outline is active -- gating on shouldOutline() alone left pure
        // Fill mode with nothing captured to fill, i.e. hands rendered nothing at all. Matches
        // EntityRendererMixin's gating for regular entities (isToggled + isValidEntity only).
        // Same Shaders > Chams > PopChams priority as ItemInHandRendererMixin -- without this,
        // the bare (itemless) hand stayed uncovered even after fixing the held-item case, since
        // this is a completely separate render path (AvatarRenderer.renderHand, not
        // ItemInHandRenderer.renderItem).
        if (shaders.isToggled() && shaders.isValidEntity(mc.player)) {
            return shaders.getColor(mc.player).getRGB() | 0xFF000000;
        }

        eu.client.modules.impl.visuals.ChamsModule chams = EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.visuals.ChamsModule.class);
        if (chams.isToggled() && chams.players.getValue() && chams.hands.getValue()) {
            return chams.getHandsFillColor(mc.player);
        }

        eu.client.modules.impl.visuals.PopChamsModule popChams = EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.visuals.PopChamsModule.class);
        if (popChams.isToggled()) {
            return popChams.fillColor.getColor().getRGB() | 0xFF000000;
        }

        return 0;
    }
}
