package eu.client.mixins;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import eu.client.EUClient;
import eu.client.events.impl.PlayerTravelEvent;
import eu.client.modules.impl.core.PatchModule;
import eu.client.modules.impl.movement.SpeedModule;
import eu.client.modules.impl.movement.VelocityModule;
import eu.client.modules.impl.player.ReachModule;
import eu.client.utils.IMinecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerEntityMixin extends LivingEntity implements IMinecraft {
    protected PlayerEntityMixin(EntityType<? extends LivingEntity> entityType, Level world) {
        super(entityType, world);
    }

    @ModifyReturnValue(method = "isPushedByFluid", at = @At("RETURN"))
    private boolean isPushedByFluids(boolean original) {
        if ((Object) this == mc.player && EUClient.MODULE_MANAGER.getModule(VelocityModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(VelocityModule.class).antiLiquidPush.getValue()) return false;
        return original;
    }

    // 2026-08-15: ported 100% from NamiDevelopment/nami-public's PatchFeature.GrimAttackVelocity
    // (replaces the deleted KeepSprintModule, which undid the SAME vanilla self-slowdown/desprint
    // AFTER the fact instead of preventing it). Player.causeExtraKnockback() -- called on the
    // ATTACKER when landing extra/critical knockback -- pushes the TARGET (must stay untouched)
    // and then also slows/desprints the ATTACKER's own client-predicted movement
    // (setDeltaMovement(...multiply(0.6, 1.0, 0.6)) + setSprinting(false)), a GrimAC quirk this
    // toggle skips for the local player only. Two @Redirects instead of cancelling the whole
    // method, since the target-push logic above those two calls must still run unconditionally.
    @Redirect(method = "causeExtraKnockback", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void grimAttackVelocity$setDeltaMovement(Entity instance, Vec3 deltaMovement) {
        if ((Object) this == mc.player && EUClient.MODULE_MANAGER.getModule(PatchModule.class).grimAttackVelocity.getValue()) return;
        instance.setDeltaMovement(deltaMovement);
    }

    @Redirect(method = "causeExtraKnockback", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setSprinting(Z)V"))
    private void grimAttackVelocity$setSprinting(Entity instance, boolean sprinting) {
        if ((Object) this == mc.player && EUClient.MODULE_MANAGER.getModule(PatchModule.class).grimAttackVelocity.getValue()) return;
        instance.setSprinting(sprinting);
    }

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void travel(Vec3 movementInput, CallbackInfo info) {
        PlayerTravelEvent event = new PlayerTravelEvent(movementInput);
        EUClient.EVENT_HANDLER.post(event);

        if (event.isCancelled()) {
            move(MoverType.SELF, getDeltaMovement());
            info.cancel();
        }
    }

    @Inject(method = "blockInteractionRange", at = @At("HEAD"), cancellable = true)
    private void getBlockInteractionRange(CallbackInfoReturnable<Double> info) {
        if (EUClient.MODULE_MANAGER.getModule(ReachModule.class).isToggled()) {
            info.setReturnValue(EUClient.MODULE_MANAGER.getModule(ReachModule.class).amount.getValue().doubleValue());
        }
    }

    @Inject(method = "entityInteractionRange", at = @At("HEAD"), cancellable = true)
    private void getEntityInteractionRange(CallbackInfoReturnable<Double> info) {
        if (EUClient.MODULE_MANAGER.getModule(ReachModule.class).isToggled()) {
            info.setReturnValue(EUClient.MODULE_MANAGER.getModule(ReachModule.class).amount.getValue().doubleValue());
        }
    }

    @Inject(method = "getSpeed", at = @At("HEAD"), cancellable = true)
    private void getMovementSpeed(CallbackInfoReturnable<Float> info) {
        if (EUClient.MODULE_MANAGER.getModule(SpeedModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(SpeedModule.class).mode.getValue().equalsIgnoreCase("Vanilla")) {
            info.setReturnValue(EUClient.MODULE_MANAGER.getModule(SpeedModule.class).vanillaSpeed.getValue().floatValue());
        }
    }
}
