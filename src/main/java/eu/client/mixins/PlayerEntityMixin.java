package eu.client.mixins;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import eu.client.EUClient;
import eu.client.events.impl.PlayerTravelEvent;
import eu.client.modules.impl.movement.KeepSprintModule;
import eu.client.modules.impl.movement.SpeedModule;
import eu.client.modules.impl.movement.VelocityModule;
import eu.client.modules.impl.player.ReachModule;
import eu.client.utils.IMinecraft;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerEntityMixin extends LivingEntity implements IMinecraft {
    protected PlayerEntityMixin(EntityType<? extends LivingEntity> entityType, Level world) {
        super(entityType, world);
    }

    @ModifyReturnValue(method = "isPushedByFluids", at = @At("RETURN"))
    private boolean isPushedByFluids(boolean original) {
        if ((Object) this == mc.player && EUClient.MODULE_MANAGER.getModule(VelocityModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(VelocityModule.class).antiLiquidPush.getValue()) return false;
        return original;
    }

    @Inject(method = "attack", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/Player;setSprinting(Z)V", shift = At.Shift.AFTER))
    private void attack(CallbackInfo callbackInfo) {
        if (EUClient.MODULE_MANAGER.getModule(KeepSprintModule.class).isToggled()) {
            float multiplier = 0.6f + 0.4f * EUClient.MODULE_MANAGER.getModule(KeepSprintModule.class).motion.getValue().floatValue();
            mc.player.setDeltaMovement(mc.player.getDeltaMovement().x / 0.6 * multiplier, mc.player.getDeltaMovement().y, mc.player.getDeltaMovement().z / 0.6 * multiplier);
            mc.player.setSprinting(true);
        }
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

    @Inject(method = "getBlockInteractionRange", at = @At("HEAD"), cancellable = true)
    private void getBlockInteractionRange(CallbackInfoReturnable<Double> info) {
        if (EUClient.MODULE_MANAGER.getModule(ReachModule.class).isToggled()) {
            info.setReturnValue(EUClient.MODULE_MANAGER.getModule(ReachModule.class).amount.getValue().doubleValue());
        }
    }

    @Inject(method = "getEntityInteractionRange", at = @At("HEAD"), cancellable = true)
    private void getEntityInteractionRange(CallbackInfoReturnable<Double> info) {
        if (EUClient.MODULE_MANAGER.getModule(ReachModule.class).isToggled()) {
            info.setReturnValue(EUClient.MODULE_MANAGER.getModule(ReachModule.class).amount.getValue().doubleValue());
        }
    }

    @Inject(method = "getMovementSpeed", at = @At("HEAD"), cancellable = true)
    private void getMovementSpeed(CallbackInfoReturnable<Float> info) {
        if (EUClient.MODULE_MANAGER.getModule(SpeedModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(SpeedModule.class).mode.getValue().equalsIgnoreCase("Vanilla")) {
            info.setReturnValue(EUClient.MODULE_MANAGER.getModule(SpeedModule.class).vanillaSpeed.getValue().floatValue());
        }
    }
}
