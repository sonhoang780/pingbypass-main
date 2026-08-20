package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.movement.ElytraFlyModule;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// ControlRocket's whole point (GrimV3 especially) is looking like you're flying WITHOUT an
// elytra -- but state.isFallFlying (extracted here, HumanoidMobRenderer#extractHumanoidRenderState)
// drives BOTH WingsLayer's wing render AND HumanoidModel's glide pose (the horizontal arms-out
// stance), regardless of what's actually on the chest slot at render time. Requested: "xóa elytra
// animation khi bay bằng ControlRocket" -- force it false for our own player while ControlRocket
// is actively toggled, so the model reads as normal falling/walking instead of the giveaway glide
// silhouette. Local-client-only illusion (same category as every other render lie in this
// codebase) -- doesn't touch what other players see of us, only our own game window.
//
// 2026-08-20 FIX (reported: "giật giật, không thấy LimbSwing như walk khi bay"). Forcing
// isFallFlying false here was never enough on its own -- verified in .mcref,
// extractHumanoidRenderState computes state.speedValue EARLIER in the same method, off the REAL
// entity.isFallFlying() (still true, this injection runs at TAIL after that already ran):
//   if (state.isFallFlying) { speedValue = deltaMovement.lengthSqr() / 0.2F; speedValue cubed; }
// ControlRocket's real flight speed makes that division explode into the hundreds/thousands, and
// HumanoidModel.setupAnim divides EVERY arm/leg swing angle by it (rightArm.xRot = ... /
// state.speedValue, same for the other three limbs) -- so limb swing amplitude collapsed toward
// zero even with the glide pose successfully hidden. Reset speedValue to vanilla's own normal
// (walking) value, 1.0F, right alongside isFallFlying so the swing formula uses the same
// denominator a real walking/falling humanoid gets.
@Mixin(HumanoidMobRenderer.class)
public abstract class HumanoidMobRendererMixin {
    @Inject(method = "extractHumanoidRenderState", at = @At("TAIL"))
    private static void euclient$hideControlRocketGlide(LivingEntity entity, HumanoidRenderState state, float partialTicks, ItemModelResolver itemModelResolver, CallbackInfo ci) {
        if (entity != net.minecraft.client.Minecraft.getInstance().player) return;

        ElytraFlyModule elytraFly = EUClient.MODULE_MANAGER.getModule(ElytraFlyModule.class);
        if (elytraFly.isToggled() && elytraFly.mode.getValue().equalsIgnoreCase("ControlRocket")) {
            state.isFallFlying = false;
            state.speedValue = 1.0f;
        }
    }
}
