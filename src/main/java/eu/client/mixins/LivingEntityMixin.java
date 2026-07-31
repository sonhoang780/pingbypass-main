package eu.client.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.item.component.SwingAnimation;
import eu.client.EUClient;
import eu.client.events.impl.ConsumeItemEvent;
import eu.client.events.impl.PlayerJumpEvent;
import eu.client.modules.impl.miscellaneous.EURoboticsModule;
import eu.client.modules.impl.movement.*;
import eu.client.modules.impl.player.SwingModule;
import eu.client.utils.IMinecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends Entity implements IMinecraft {
    @Shadow public abstract @Nullable AttributeInstance getAttribute(Holder<Attribute> attribute);

    @Shadow @Final private static AttributeModifier SPEED_MODIFIER_SPRINTING;

    @Shadow private int noJumpDelay;

    @Shadow protected ItemStack useItem;

    public LivingEntityMixin(EntityType<?> type, Level world) {
        super(type, world);
    }

    @WrapMethod(method = "maxUpStep")
    private float getStepHeight(Operation<Float> original) {
        if ((Object) this == mc.player && EUClient.MODULE_MANAGER != null && ((EUClient.MODULE_MANAGER.getModule(StepModule.class).isToggled() && mc.player.onGround()) || (EUClient.MODULE_MANAGER.getModule(HoleSnapModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(HoleSnapModule.class).step.getValue()) /*|| EUClient.MODULE_MANAGER.getModule(EURoboticsModule.class).shouldStep()*/)) {
            return EUClient.MODULE_MANAGER.getModule(StepModule.class).height.getValue().floatValue();
        }

        return original.call();
    }

    @Inject(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;pop()V", ordinal = 2, shift = At.Shift.BEFORE))
    private void doItemUse(CallbackInfo info) {
        if (EUClient.MODULE_MANAGER != null && EUClient.MODULE_MANAGER.getModule(NoJumpDelayModule.class).isToggled() && noJumpDelay == 10) {
            noJumpDelay = EUClient.MODULE_MANAGER.getModule(NoJumpDelayModule.class).ticks.getValue().intValue();
        }
    }

    @WrapOperation(method = "getCurrentSwingDuration", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/component/SwingAnimation;duration()I"))
    private int getHandSwingDuration(SwingAnimation instance, Operation<Integer> original) {
        int constant = original.call(instance);
        if ((Object) this != mc.player) return constant;
        return EUClient.MODULE_MANAGER.getModule(SwingModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(SwingModule.class).modifySpeed.getValue() && mc.options.getCameraType().isFirstPerson() ? (21 - EUClient.MODULE_MANAGER.getModule(SwingModule.class).speed.getValue().intValue()) : constant;
    }

    @Inject(method = "completeUsingItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;finishUsingItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;", shift = At.Shift.AFTER))
    private void consumeItem(CallbackInfo ci) {
        if((Object) this == mc.player) EUClient.EVENT_HANDLER.post(new ConsumeItemEvent(useItem));
    }

    @Inject(method = "setSprinting", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setSprinting(Z)V", shift = At.Shift.AFTER), cancellable = true)
    private void setSprinting$setSprinting(boolean sprinting, CallbackInfo info) {
        if ((Object) this == mc.player && EUClient.MODULE_MANAGER.getModule(SprintModule.class).isToggled()) {
            AttributeInstance entityAttributeInstance = getAttribute(Attributes.MOVEMENT_SPEED);
            entityAttributeInstance.removeModifier(SPEED_MODIFIER_SPRINTING.id());

            if (EUClient.MODULE_MANAGER.getModule(SprintModule.class).shouldSprint()) {
                setSharedFlag(3, true);
                entityAttributeInstance.addTransientModifier(SPEED_MODIFIER_SPRINTING);
            } else {
                setSharedFlag(3, false);
            }

            info.cancel();
        }
    }

    @Inject(method = "jumpFromGround", at = @At("HEAD"))
    private void jump$HEAD(CallbackInfo info) {
        if ((Object) this != mc.player) return;
        EUClient.EVENT_HANDLER.post(new PlayerJumpEvent());
    }

    @Inject(method = "jumpFromGround", at = @At("RETURN"))
    private void jump$RETURN(CallbackInfo info) {
        if ((Object) this != mc.player) return;
        EUClient.EVENT_HANDLER.post(new PlayerJumpEvent.Post());
    }
}
