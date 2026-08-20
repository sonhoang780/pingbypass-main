package eu.client.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import eu.client.EUClient;
import eu.client.events.impl.ChangePitchEvent;
import eu.client.events.impl.ChangeYawEvent;
import eu.client.events.impl.UpdateVelocityEvent;
import eu.client.modules.impl.movement.NoSlowModule;
import eu.client.modules.impl.movement.VelocityModule;
import eu.client.modules.impl.player.RotationLockModule;
import eu.client.modules.impl.visuals.FreecamModule; // Thêm import FreecamModule
import eu.client.modules.impl.visuals.PopChamsModule;
import eu.client.utils.IMinecraft;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityFluidInteraction;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin implements IMinecraft {
    
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void euclient$cancelGhostTick(CallbackInfo ci) {
        if ((Object) this instanceof net.minecraft.client.player.RemotePlayer ghost) {
            PopChamsModule popChams = EUClient.MODULE_MANAGER != null ? EUClient.MODULE_MANAGER.getModule(PopChamsModule.class) : null;
            if (popChams != null && popChams.isGhost(ghost)) {
                ci.cancel();
                return;
            }
            eu.client.modules.impl.visuals.LogoutSpotModule logoutSpot = EUClient.MODULE_MANAGER != null ? EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.visuals.LogoutSpotModule.class) : null;
            if (logoutSpot != null && logoutSpot.isGhost(ghost)) {
                ci.cancel();
            }
        }
    }

    // --- [THÊM MỚI] Inject chặn sự kiện xoay chuột của Freecam ---
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    public void onTurn(double cursorDeltaYaw, double cursorDeltaPitch, CallbackInfo ci) {
        if ((Object) this == mc.player) {
            FreecamModule freecam = EUClient.MODULE_MANAGER.getModule(FreecamModule.class);
            
            if (freecam != null && freecam.isToggled() && !freecam.getRotate().getValue()) {
                freecam.onMouseTurn(cursorDeltaYaw, cursorDeltaPitch);
                ci.cancel();
            }
        }
    }
    // -------------------------------------------------------------

    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void pushAwayFrom(Entity entity, CallbackInfo info) {
        if ((Object) this == mc.player && EUClient.MODULE_MANAGER.getModule(VelocityModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(VelocityModule.class).antiPush.getValue()) {
            info.cancel();
        }
    }

    // PORT: fluid-current pushing moved from a getVelocity()-modify point into
    // EntityFluidInteraction.applyCurrentTo(...), invoked from updateFluidInteraction().
    @WrapOperation(method = "updateFluidInteraction", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/EntityFluidInteraction;applyCurrentTo(Lnet/minecraft/tags/TagKey;Lnet/minecraft/world/entity/Entity;D)V"))
    private void updateMovementInFluid(EntityFluidInteraction instance, TagKey<Fluid> fluid, Entity entity, double motionScale, Operation<Void> original) {
        if ((Object) this == mc.player && EUClient.MODULE_MANAGER.getModule(VelocityModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(VelocityModule.class).antiLiquidPush.getValue()) {
            return;
        }

        original.call(instance, fluid, entity, motionScale);
    }

    @Inject(method = "moveRelative", at = @At("HEAD"), cancellable = true)
    private void updateVelocity(float speed, Vec3 movementInput, CallbackInfo info) {
        if ((Object) this != mc.player) return;

        UpdateVelocityEvent event = new UpdateVelocityEvent(movementInput, speed);
        EUClient.EVENT_HANDLER.post(event);
        if (event.isCancelled()) {
            info.cancel();
            mc.player.setDeltaMovement(mc.player.getDeltaMovement().add(event.getVelocity()));
        }
    }

    @Inject(method = "getYRot*", at = @At("HEAD"), cancellable = true)
    private void getYaw(CallbackInfoReturnable<Float> info) {
        if (EUClient.MODULE_MANAGER.getModule(RotationLockModule.class).isToggled() && (EUClient.MODULE_MANAGER.getModule(RotationLockModule.class).mode.getValue().equals("Yaw") || EUClient.MODULE_MANAGER.getModule(RotationLockModule.class).mode.getValue().equals("Both")) && (Object) this == mc.player) {
            info.setReturnValue(EUClient.MODULE_MANAGER.getModule(RotationLockModule.class).yaw.getValue().floatValue());
        }
    }

    @Inject(method = "getXRot*", at = @At("HEAD"), cancellable = true)
    private void getPitch(CallbackInfoReturnable<Float> info) {
        if (EUClient.MODULE_MANAGER.getModule(RotationLockModule.class).isToggled() && (EUClient.MODULE_MANAGER.getModule(RotationLockModule.class).mode.getValue().equals("Pitch") || EUClient.MODULE_MANAGER.getModule(RotationLockModule.class).mode.getValue().equals("Both")) && (Object) this == mc.player) {
            info.setReturnValue(EUClient.MODULE_MANAGER.getModule(RotationLockModule.class).pitch.getValue().floatValue());
        }
    }

    // ControlRocket's camera-decouple, re-verified against example-addon-master's own MixinEntity
    // (src/main/java/com/example/addon/mixin/MixinEntity.java): the reference ONLY overrides the
    // tickDelta-taking overload -- `getXRot(F)F`/`getYRot(F)F`, used for RENDER interpolation
    // (first-person arm, HeldItemRenderer, Camera.update) -- and deliberately leaves the no-arg
    // `getYRot()`/`getXRot()` alone, because that's what vanilla's own physics/packet code calls
    // directly. Our port previously used the wildcard `getYRot*`/`getXRot*` above for this too,
    // which matches BOTH overloads -- so it ALSO intercepted the no-arg calls .mcref confirmed
    // LocalPlayer#sendPosition (and travel()/aiStep()) actually make, feeding them the saved
    // CAMERA yaw instead of targetYaw for the entire tick. That's why WASD only ever steered the
    // packet's rotation label, not the real flight direction or the firework boost -- reported
    // "chỉ điều khiển hướng bay qua chuột", "không bay lên được". A prior fix pass removed the
    // override entirely on the theory that render never interleaves with tick() so it can't
    // matter -- wrong fix for the wrong half of the problem; the reference's own working design
    // already scopes it correctly, so match that instead of deleting it.
    @Inject(method = "getYRot(F)F", at = @At("HEAD"), cancellable = true)
    private void controlRocket$overrideYaw(float tickDelta, CallbackInfoReturnable<Float> info) {
        if ((Object) this != mc.player) return;
        var elytraFly = EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.movement.ElytraFlyModule.class);
        if (elytraFly.isCrCameraOverrideActive()) info.setReturnValue(elytraFly.getCrSavedYaw());
    }

    @Inject(method = "getXRot(F)F", at = @At("HEAD"), cancellable = true)
    private void controlRocket$overridePitch(float tickDelta, CallbackInfoReturnable<Float> info) {
        if ((Object) this != mc.player) return;
        var elytraFly = EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.movement.ElytraFlyModule.class);
        if (elytraFly.isCrCameraOverrideActive()) info.setReturnValue(elytraFly.getCrSavedPitch());
    }

    @Inject(method = "setYRot", at = @At("HEAD"), cancellable = true)
    private void setYaw(float yaw, CallbackInfo info) {
        if ((Object) this != mc.player) return;
        EUClient.EVENT_HANDLER.post(new ChangeYawEvent(yaw));
    }

    @Inject(method = "setXRot", at = @At("HEAD"), cancellable = true)
    private void setPitch(float pitch, CallbackInfo info) {
        if ((Object) this != mc.player) return;
        EUClient.EVENT_HANDLER.post(new ChangePitchEvent(pitch));
    }

    @ModifyExpressionValue(method = "getBlockSpeedFactor", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getBlock()Lnet/minecraft/world/level/block/Block;"))
    private Block getVelocityMultiplier(Block original) {
        if (EUClient.MODULE_MANAGER.getModule(NoSlowModule.class).isToggled()) {
            if ((original == Blocks.SOUL_SAND && EUClient.MODULE_MANAGER.getModule(NoSlowModule.class).soulSand.getValue()) || (original == Blocks.HONEY_BLOCK && EUClient.MODULE_MANAGER.getModule(NoSlowModule.class).honeyBlocks.getValue())) {
                return Blocks.STONE;
            }
        }

        return original;
    }
}