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
